package com.agentops.model;

import com.agentops.api.RunApplicationService.BadRequestException;
import com.agentops.domain.Primitives.Principal;
import com.agentops.infra.Json;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Probe the same streaming/tool protocol as real tasks, before persisting a configuration. */
@Service
public class ModelConnectionVerifier {
    private final OpenAiChatClient client;
    private final Json json;
    private final Clock clock;
    private final Map<String,Instant> verified=new LinkedHashMap<>();
    public ModelConnectionVerifier(OpenAiChatClient client,Json json,Clock clock) {
        this.client=client;this.json=json;this.clock=clock;
    }
    public void require(Principal owner,ModelSettingsService.ChatConfig config) {
        String fingerprint=fingerprint(owner,config);
        synchronized(verified) {
            var until=verified.get(fingerprint);
            if(until!=null && until.isAfter(Instant.now(clock)))return;
        }
        var result=test(owner,config);
        if(!Boolean.TRUE.equals(result.get("ok")))throw new BadRequestException("配置未保存："+result.get("message"));
    }
    public Map<String,Object> test(Principal owner,ModelSettingsService.ChatConfig config) {
        String fingerprint=fingerprint(owner,config);
        synchronized(verified){verified.remove(fingerprint);}
        var tool=Map.<String,Object>of("type","function","function",Map.of("name","finish_task",
                "description","提交连接检测结果","parameters",Map.of("type","object","properties",Map.of(
                        "answer",Map.of("type","string")),"required",List.of("answer"))));
        var streamed=new AtomicBoolean();
        // Keep configuration probes shorter than the UI's 120s request deadline.
        // Long-running tasks retain their independent, longer streaming limits.
        var probeDeadline=Instant.now().plusSeconds(90);
        try {
            var control=new ModelPort.ExecutionControl() {
                public boolean cancellationRequested(){return false;}
                public Instant deadlineAt(){return probeDeadline;}
            };
            var result=client.stream(config,List.of(ModelPort.ModelMessage.user(
                    "AGENTOPS_CONNECTION_CHECK：只调用finish_task，answer填OK，不执行其他操作。")),
                    List.of(tool),512,control,part->streamed.set(true));
            boolean valid=result.complete() && result.toolCalls()!=null && result.toolCalls().size()==1
                    && "finish_task".equals(result.toolCalls().get(0).name())
                    && !json.tree(result.toolCalls().get(0).argumentsJson()).path("answer").asText().isBlank();
            if(!valid)return Map.of("ok",false,"message","服务未返回完整有效的工具调用，请选择兼容工具调用的模型");
            synchronized(verified) {
                verified.entrySet().removeIf(e->!e.getValue().isAfter(Instant.now(clock)));
                while(verified.size()>=512)verified.remove(verified.keySet().iterator().next());
                verified.put(fingerprint,Instant.now(clock).plusSeconds(300));
            }
            return Map.of("ok",true,"streaming",streamed.get(),"message",streamed.get()
                    ?"连接正常，已验证模型、流式响应及工具调用"
                    :"连接正常，已验证模型及工具调用；此服务以整段方式返回响应");
        }catch(RuntimeException error) {
            String code=error instanceof OpenAiChatClient.ModelUnavailableException m?m.code():"";
            String status=code.startsWith("MODEL_HTTP_")?code.substring("MODEL_HTTP_".length()):"";
            String message=switch(status) {
                case "401","403"->"Key 无效或没有模型访问权限";
                case "404"->"接口或模型不存在，请检查 URL 和模型名";
                case "400","422"->"接口不兼容当前流式工具调用协议，请检查 URL 和模型";
                case "429"->"服务限流或配额不足，请稍后再试";
                default->"连接或流式检测失败，请检查服务地址、网络和模型服务";
            };
            return Map.of("ok",false,"message",message+(status.isEmpty()?"":"（HTTP "+status+"）"));
        }
    }
    private String fingerprint(Principal owner,ModelSettingsService.ChatConfig config) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(String part:List.of(owner.tenantId().toString(),owner.userId().toString(),config.url(),config.model(),config.key())) {
                byte[] bytes=part.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
