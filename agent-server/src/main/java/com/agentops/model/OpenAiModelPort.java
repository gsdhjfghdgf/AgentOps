package com.agentops.model;

import com.agentops.config.AgentProperties;
import com.agentops.domain.ModelDecision;
import com.agentops.domain.Primitives.RunSnapshot;
import com.agentops.domain.ResultTypes.CandidateAnswer;
import com.agentops.domain.ToolTypes.ToolSpec;
import com.agentops.infra.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * OpenAI 兼容模型适配器（文档 13.1 / 27 节）。
 *
 * 关键约束：
 * - 只发出一次模型请求，不自动执行工具（文档"同一运行链路只能有一套工具执行循环"）；
 * - 工具调用意图原样返回给引擎，由 ToolGateway 统一登记、授权、预算与执行；
 * - 流式展示公开说明及回答，但完整参数和结束标记到齐后才交给引擎执行工具。
 */
@Component
public class OpenAiModelPort implements ModelPort {

    private static final Logger log = LoggerFactory.getLogger(OpenAiModelPort.class);

    private final OpenAiChatClient client;
    private final AgentProperties props;
    private final Json json;
    private final ModelSettingsService settings;

    public OpenAiModelPort(OpenAiChatClient client, AgentProperties props, Json json, ModelSettingsService settings) {
        this.client = client;
        this.props = props;
        this.json = json; this.settings=settings;
    }

    @Override
    public ModelDecision next(RunSnapshot snapshot, String systemPrompt, List<ModelMessage> messages,
                              List<ToolSpec> allowedTools, ExecutionControl control,
                              Consumer<String> provisional) {
        return request(snapshot, systemPrompt, messages, allowedTools, control, provisional).decision();
    }

    @Override
    public Response request(RunSnapshot snapshot, String systemPrompt, List<ModelMessage> messages,
                            List<ToolSpec> allowedTools, ExecutionControl control, Consumer<String> provisional) {
        return request(snapshot,systemPrompt,messages,allowedTools,control,provisional,s->{});
    }

    @Override public Response request(RunSnapshot snapshot,String systemPrompt,List<ModelMessage> messages,
                                     List<ToolSpec> allowedTools,ExecutionControl control,Consumer<String> provisional,Consumer<String> commentary){
        if (control.cancellationRequested()) throw new ModelPort.Cancelled();
        if (com.agentops.domain.RunMode.directConversation(snapshot)) {
            var conversation = new ArrayList<ModelMessage>();
            conversation.add(ModelMessage.system(systemPrompt));
            for (var message : messages)
                if (("user".equals(message.role()) || "assistant".equals(message.role()))
                        && (message.toolCalls() == null || message.toolCalls().isEmpty()))
                    conversation.add(message);
            var streamed = new java.util.concurrent.atomic.AtomicBoolean();
            var result = client.stream(settings.forRun(snapshot), conversation, List.of(),
                    props.budget().maxOutputTokensPerCall(), control, part -> {
                        if ("commentary".equals(part.channel())) {
                            streamed.set(true);
                            provisional.accept(part.text());
                        }
                    });
            if (!result.complete() || (result.toolCalls() != null && !result.toolCalls().isEmpty())
                    || result.content() == null || result.content().isBlank())
                throw new IllegalArgumentException("聊天响应不完整或返回了未请求的工具调用");
            if (!streamed.get()) provisional.accept(result.content());
            var usage = result.usage() == null ? null : com.agentops.domain.ResultTypes.Usage.of(
                    result.usage().promptTokens(), result.usage().completionTokens());
            return new Response(new ModelDecision.FinalCandidate(new CandidateAnswer(
                    UUID.randomUUID(), result.content(), List.of(), List.of(), Map.of())), usage);
        }
        var wireMessages = new ArrayList<ModelMessage>();
        if (systemPrompt != null && !systemPrompt.isBlank()) wireMessages.add(ModelMessage.system(systemPrompt));
        boolean chat=snapshot!=null && snapshot.mode()==com.agentops.domain.RunMode.CHAT;
        wireMessages.add(ModelMessage.system((chat
                ?"当前是聊天模式：常识和信息明确的问题直接用正文回答；不必为了常识反复搜索。需要实时资料或用户要求核实时再调用工具，工具返回后及时给出答案。"
                :"先判断问题：寒暄、常识及信息明确的问题可以直接用 finish_task 回答，不能因此要求用户选择业务类型或补充任务。")
                + "需要查询资料时使用可见工具；真正缺少必要信息时才单独调用 ask_user；"
                + (chat?"完成查询后直接给出最终正文。":"任务已完成时单独调用 finish_task 提交最终答案。")
                + "提问时提供2到4个简短可选答案，界面另有自行补充入口。"
                + "澄清问题不能放进 finish_task，不能把口头要求批准当作完成。不要编造参数。"
                + "在普通 content 中用一两句话说明当前行动、依据或阶段成果，避免输出内部推演。"
                + "长任务取得实质发现后使用 task_notes 保存简短阶段结论和文件/行号等证据；不要反复发送空进度。"));
        wireMessages.addAll(messages);
        var tools = new ArrayList<Map<String, Object>>();
        ModelToolCatalog.select(snapshot, allowedTools, messages, json).forEach(tool ->
                tools.add(Map.of("type", "function", "function", tool.toOpenAiFunction())));
        tools.add(Map.of("type", "function", "function", Map.of(
                "name", "ask_user", "description", "缺少必要信息时向用户澄清，暂停任务等待回复。必须单独调用。",
                "parameters", Map.of("type", "object", "properties", Map.of("question", Map.of("type", "string"),
                        "options",Map.of("type","array","items",Map.of("type","string"),"minItems",2,"maxItems",4)),
                        "required", List.of("question","options"), "additionalProperties", false))));
        if(!chat)tools.add(Map.of("type", "function", "function", Map.of(
                "name", "finish_task", "description", "任务完成后提交最终答案；缺少必要信息时必须改用 ask_user。必须单独调用。",
                "parameters", Map.of("type", "object", "properties", Map.of("answer", Map.of("type", "string")),
                        "required", List.of("answer"), "additionalProperties", false))));
        var answerStreamed=new java.util.concurrent.atomic.AtomicBoolean();var commentaryStreamed=new java.util.concurrent.atomic.AtomicBoolean();
        var result = client.stream(settings.forRun(snapshot),wireMessages,tools,props.budget().maxOutputTokensPerCall(),control,part->{
            if("answer".equals(part.channel()) || chat){answerStreamed.set(true);provisional.accept(part.text());}
            else{commentaryStreamed.set(true);commentary.accept(part.text());}
        });
        if(!chat&&!commentaryStreamed.get()&&result.content()!=null&&!result.content().isBlank())commentary.accept(result.content());
        var usage = result.usage() == null ? null : com.agentops.domain.ResultTypes.Usage.of(
                result.usage().promptTokens(), result.usage().completionTokens());
        if(chat && result.complete() && (result.toolCalls()==null || result.toolCalls().isEmpty())
                && result.content()!=null && !result.content().isBlank()) {
            if(!answerStreamed.get())provisional.accept(result.content());
            return new Response(new ModelDecision.FinalCandidate(new CandidateAnswer(UUID.randomUUID(),
                    result.content(),List.of(),List.of(),Map.of())),usage);
        }
        return new Response(toDecision(result,text->{if(!answerStreamed.get())provisional.accept(text);}), usage);
    }

    private ModelDecision toDecision(OpenAiChatClient.ChatResult result, Consumer<String> provisional) {
        if (!result.complete()) {
            boolean length = "length".equals(result.finishReason());
            log.warn("模型响应未完整结束 finishReason={} promptTokens={} completionTokens={}", result.finishReason(),
                    result.usage()==null?-1:result.usage().promptTokens(),result.usage()==null?-1:result.usage().completionTokens());
            throw new OpenAiChatClient.ModelUnavailableException(length ? "模型达到单次输出上限，未执行不完整工具调用" : "模型响应未完整结束",
                    true,null,null,length?"MODEL_OUTPUT_TRUNCATED":"MODEL_INVALID_RESPONSE",result.usage());
        }
        if (result.toolCalls() != null && !result.toolCalls().isEmpty()) {
            var finishes = result.toolCalls().stream().filter(c -> "finish_task".equals(c.name())).toList();
            if (!finishes.isEmpty()) {
                if (result.toolCalls().size()!=1) throw new IllegalArgumentException("提交最终答案不能与其他行动混用");
                Object answer = parseArgs(finishes.get(0).argumentsJson()).get("answer");
                if (!(answer instanceof String text) || text.isBlank()) throw new IllegalArgumentException("最终答案为空");
                provisional.accept(text);
                return new ModelDecision.FinalCandidate(new CandidateAnswer(UUID.randomUUID(),text,List.of(),List.of(),Map.of()));
            }
            var questions = result.toolCalls().stream().filter(c -> "ask_user".equals(c.name())).toList();
            if (!questions.isEmpty()) {
                if (result.toolCalls().size() != 1) throw new IllegalArgumentException("澄清问题不能与执行工具混用");
                var arguments=parseArgs(questions.get(0).argumentsJson());
                Object question = arguments.get("question");
                if (!(question instanceof String text) || text.isBlank() || text.length() > 2000)
                    throw new IllegalArgumentException("澄清问题无效");
                var options=new ArrayList<String>();
                if(arguments.get("options") instanceof List<?> list)for(var value:list) {
                    if(!(value instanceof String option) || option.isBlank() || option.length()>200 || options.size()>=4)
                        throw new IllegalArgumentException("问题选项无效");
                    if(!options.contains(option))options.add(option);
                }
                // Legacy model adapters may omit options; the inline free-text answer remains available.
                return new ModelDecision.AskUser(UUID.randomUUID(), text,options);
            }
            var calls = result.toolCalls().stream()
                    .map(c -> new com.agentops.domain.ToolTypes.ToolCallRequest(
                            c.id(), c.name(), parseArgs(c.argumentsJson())))
                    .toList();
            return new ModelDecision.ToolBatch(result.content(), calls);
        }
        throw new IllegalArgumentException("模型未返回明确行动，拒绝把普通正文误判为任务完成");
    }

    private Map<String, Object> parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return Map.of();
        try {
            return json.map(argsJson);
        } catch (RuntimeException e) {
            log.warn("工具参数不是合法 JSON: {}", e.getMessage());
            throw new IllegalArgumentException("工具参数不是完整的 JSON 对象", e);
        }
    }
}
