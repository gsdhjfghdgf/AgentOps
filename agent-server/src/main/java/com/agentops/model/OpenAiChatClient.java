package com.agentops.model;

import com.agentops.config.AgentProperties;
import com.agentops.infra.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OpenAI 兼容协议客户端（chat/completions + embeddings）。
 * DeepSeek / 通义 / Moonshot / OpenAI 等兼容服务只需改 base-url 与模型名。
 *
 * 约束（文档 27.4）：本客户端不隐藏重试——重试由引擎按预算统一控制；
 * 限流(429)/暂时不可用(5xx)以明确异常抛出，由上层按退避策略处理。
 */
@Component
public class OpenAiChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatClient.class);

    private final RestClient rest;
    private final AgentProperties props;
    private final Json json;

    public OpenAiChatClient(RestClient restClient, AgentProperties props, Json json) {
        this.rest = restClient;
        this.props = props;
        this.json = json;
    }

    /** 模型不可用类异常：携带是否可重试与服务端建议等待时间。 */
    public static class ModelUnavailableException extends RuntimeException {
        private final boolean retryable;
        private final Long suggestedWaitSeconds;
        private final String code;

        public ModelUnavailableException(String message, boolean retryable, Long suggestedWaitSeconds, Throwable cause) {
            this(message,retryable,suggestedWaitSeconds,cause,"MODEL_NETWORK_ERROR");
        }
        public ModelUnavailableException(String message, boolean retryable, Long suggestedWaitSeconds, Throwable cause,String code) {
            super(message, cause);
            this.retryable = retryable;
            this.suggestedWaitSeconds = suggestedWaitSeconds;
            this.code=code;
        }
        public String code(){return code;}

        public boolean retryable() {
            return retryable;
        }

        public Long suggestedWaitSeconds() {
            return suggestedWaitSeconds;
        }
    }

    public record ChatUsage(long promptTokens, long completionTokens) {
    }

    public record ChatResult(String content, List<com.agentops.model.ModelPort.ToolCall> toolCalls,
                             ChatUsage usage, boolean complete) {
    }

    /** 单次 chat/completions 请求（非流式；引擎需要完整工具调用与 usage 元数据，文档 20.3）。 */
    public ChatResult chat(String model, List<ModelPort.ModelMessage> messages,
                           List<Map<String, Object>> tools, int maxOutputTokens) {
        var defaults=ModelSettingsService.defaults(props);
        return chat(new ModelSettingsService.ChatConfig(defaults.profileId(),defaults.provider(),defaults.url(),model,defaults.key()),
                messages,tools,maxOutputTokens);
    }

    public ChatResult chat(ModelSettingsService.ChatConfig config,List<ModelPort.ModelMessage> messages,
                           List<Map<String,Object>> tools,int maxOutputTokens) {
        var body=requestBody(config,messages,tools,maxOutputTokens);
        try {
            var response = rest.post().uri(config.url())
                    .headers(headers -> { if(!config.key().isBlank()) headers.setBearerAuth(config.key()); })
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(json.write(body))
                    .retrieve().body(String.class);
            return parseChatResult(response);
        } catch (RestClientResponseException e) {
            throw httpFailure(e.getStatusCode().value(),e.getResponseHeaders()==null?null:e.getResponseHeaders().getFirst("retry-after"),e);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            throw new ModelUnavailableException("模型请求网络异常，请检查连接",true,null,e);
        }
    }

    private Map<String,Object> requestBody(ModelSettingsService.ChatConfig config,List<ModelPort.ModelMessage> messages,List<Map<String,Object>> tools,int maxOutputTokens){
        var body = new LinkedHashMap<String, Object>();
        body.put("model", config.model());
        body.put("messages", messages.stream().map(this::toWireMessage).toList());
        body.put("max_tokens", maxOutputTokens);
        if ("api.deepseek.com".equals(java.net.URI.create(config.url()).getHost())) {
            body.put("thinking", Map.of("type", "disabled"));
        }
        if (tools != null && !tools.isEmpty()) {
            body.put("tools", tools);
            boolean decisions = tools.stream().anyMatch(tool ->
                    tool.get("function") instanceof Map<?,?> function && "finish_task".equals(function.get("name")));
            body.put("tool_choice", decisions ? "required" : "auto");
        }

        return body;
    }

    private ModelUnavailableException httpFailure(int status,String retryAfter,Throwable cause){
        Long wait=null;if(status==429&&retryAfter!=null)try{wait=Long.parseLong(retryAfter);}catch(NumberFormatException ignored){}
        return new ModelUnavailableException("模型服务返回 "+status+"：请检查服务地址、模型名、Key 或配额",
                status==429||status>=500,wait,cause,"MODEL_HTTP_"+status);
    }

    private final java.util.concurrent.ScheduledExecutorService streamWatch=java.util.concurrent.Executors.newScheduledThreadPool(2,r->{var t=new Thread(r,"model-stream-watch");t.setDaemon(true);return t;});
    @jakarta.annotation.PreDestroy void closeStreams(){streamWatch.shutdownNow();}

    public ChatResult stream(ModelSettingsService.ChatConfig config,List<ModelPort.ModelMessage> messages,
                             List<Map<String,Object>> tools,int maxOutputTokens,ModelPort.ExecutionControl control,
                             java.util.function.Consumer<ChatStreamAccumulator.Part> publish){
        var body=requestBody(config,messages,tools,maxOutputTokens);body.put("stream",true);body.put("stream_options",Map.of("include_usage",true));
        var input=new java.util.concurrent.atomic.AtomicReference<java.io.InputStream>();var done=new java.util.concurrent.atomic.AtomicBoolean();var cancelled=new java.util.concurrent.atomic.AtomicBoolean();var timeout=new java.util.concurrent.atomic.AtomicBoolean();
        Thread runner=Thread.currentThread();var until=java.time.Instant.now().plusSeconds(props.model().timeoutSeconds());
        var watch=streamWatch.scheduleWithFixedDelay(()->{if(done.get())return;boolean stop=false;try{stop=control.cancellationRequested()||control.exceeded(java.time.Instant.now());}catch(RuntimeException e){stop=true;}
            boolean expired=java.time.Instant.now().isAfter(until);if((stop||expired)&&!done.get()){cancelled.set(stop);timeout.set(expired);runner.interrupt();var stream=input.get();if(stream!=null)try{stream.close();}catch(java.io.IOException ignored){}}
        },200,200,java.util.concurrent.TimeUnit.MILLISECONDS);
        try{
            if(control.cancellationRequested())throw new ModelPort.Cancelled();
            return rest.post().uri(config.url()).headers(h->{if(!config.key().isBlank())h.setBearerAuth(config.key());h.setAccept(List.of(org.springframework.http.MediaType.TEXT_EVENT_STREAM,org.springframework.http.MediaType.APPLICATION_JSON));})
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(json.write(body)).exchange((request,response)->{
                        int status=response.getStatusCode().value();if(status<200||status>=300)throw httpFailure(status,response.getHeaders().getFirst("retry-after"),null);
                        var raw=response.getBody();input.set(raw);String type=Objects.toString(response.getHeaders().getContentType(),"");
                        if(!type.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream")){byte[] bytes=raw.readNBytes(2_000_001);if(bytes.length>2_000_000)throw new IllegalArgumentException("模型响应过大");return parseChatResult(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));}
                        var accumulator=new ChatStreamAccumulator();var reader=new java.io.BufferedReader(new java.io.InputStreamReader(raw,java.nio.charset.StandardCharsets.UTF_8));var event=new StringBuilder();String line;int size=0;
                        while((line=reader.readLine())!=null){if(cancelled.get())throw new ModelPort.Cancelled();if(timeout.get())throw new java.io.IOException("模型响应超时");size+=line.length();if(size>2_000_000)throw new IllegalArgumentException("模型流响应过大");
                            if(line.startsWith("data:"))event.append(line.substring(5).stripLeading()).append('\n');
                            else if(line.isBlank()&&!event.isEmpty()){String value=event.toString().strip();event.setLength(0);if(value.equals("[DONE]"))break;accumulator.accept(json.tree(value),publish);}
                        }
                        if(!event.isEmpty()&&!event.toString().strip().equals("[DONE]"))accumulator.accept(json.tree(event.toString()),publish);
                        if(cancelled.get())throw new ModelPort.Cancelled();if(timeout.get())throw new java.io.IOException("模型响应超时");return accumulator.finish();
                    });
        }catch(org.springframework.web.client.RestClientException e){if(cancelled.get())throw new ModelPort.Cancelled();throw new ModelUnavailableException(timeout.get()?"模型流式响应超时":"模型流连接中断，请稍后重试",true,null,e,timeout.get()?"MODEL_TIMEOUT":"MODEL_NETWORK_ERROR");
        }finally{done.set(true);watch.cancel(false);input.set(null);if(cancelled.get()||timeout.get())Thread.interrupted();}
    }

    /** embedding 请求（OpenAI 兼容 /v1/embeddings）。 */
    public float[] embed(String model, String input, int dimensions) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("input", List.of(input));
        if (dimensions > 0) body.put("dimensions", dimensions);
        try {
            var response = rest.post()
                    .uri(AgentProperties.Model.endpoint(props.model().embeddingBaseUrl(), "embeddings"))
                    .headers(headers -> {
                        String key = props.model().embeddingApiKey();
                        if (key != null && !key.isBlank()) headers.setBearerAuth(key);
                    })
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(json.write(body))
                    .retrieve()
                    .body(String.class);
            var tree = json.tree(response);
            var first = tree.get("data").get(0).get("embedding");
            var result = new float[first.size()];
            for (int i = 0; i < first.size(); i++) {
                result[i] = (float) first.get(i).asDouble();
            }
            return result;
        } catch (RestClientResponseException | org.springframework.web.client.ResourceAccessException e) {
            throw new ModelUnavailableException("embedding 请求失败: " + e.getMessage(), true, null, e);
        }
    }

    // ==================== 解析 ====================

    private Map<String, Object> toWireMessage(ModelPort.ModelMessage m) {
        var wire = new LinkedHashMap<String, Object>();
        wire.put("role", m.role());
        if ("assistant".equals(m.role())) {
            if (m.content() != null) wire.put("content", m.content());
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                var calls = new ArrayList<Object>();
                for (var c : m.toolCalls()) {
                    var fn = new LinkedHashMap<String, Object>();
                    fn.put("name", c.name());
                    fn.put("arguments", c.argumentsJson());
                    var call = new LinkedHashMap<String, Object>();
                    call.put("id", c.id());
                    call.put("type", "function");
                    call.put("function", fn);
                    calls.add(call);
                }
                wire.put("tool_calls", calls);
            }
        } else if ("tool".equals(m.role())) {
            wire.put("tool_call_id", m.toolCallId());
            wire.put("content", m.content());
        } else {
            wire.put("content", m.content() == null ? "" : m.content());
        }
        return wire;
    }

    private ChatResult parseChatResult(String responseJson) {
        var tree = json.tree(responseJson);
        var choice = tree.get("choices").get(0);
        var message = choice.get("message");
        String content = message.has("content") && !message.get("content").isNull()
                ? message.get("content").asText() : null;
        String finishReason = choice.has("finish_reason") ? choice.get("finish_reason").asText() : null;

        var calls = new ArrayList<ModelPort.ToolCall>();
        if (message.has("tool_calls") && !message.get("tool_calls").isNull()) {
            for (var node : message.get("tool_calls")) {
                var fn = node.get("function");
                calls.add(new ModelPort.ToolCall(
                        node.get("id").asText(),
                        fn.get("name").asText(),
                        fn.get("arguments").asText()));
            }
        }

        long promptTokens = 0;
        long completionTokens = 0;
        if (tree.has("usage") && !tree.get("usage").isNull()) {
            var usage = tree.get("usage");
            if (usage.has("prompt_tokens")) promptTokens = usage.get("prompt_tokens").asLong();
            if (usage.has("completion_tokens")) completionTokens = usage.get("completion_tokens").asLong();
        }

        // 内容未完成或输出截断（文档 27.2）：标记不完整，不把截断响应提交为最终结果
        boolean complete = "stop".equals(finishReason) || "tool_calls".equals(finishReason);
        if (!complete) {
            log.warn("模型输出被截断 finish_reason=length");
        }
        return new ChatResult(content, calls, tree.hasNonNull("usage")
                ? new ChatUsage(promptTokens, completionTokens) : null, complete);
    }
}
