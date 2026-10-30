package com.agentops.api.web;

import com.agentops.config.AgentProperties;
import com.agentops.model.EmbeddingRouter;
import com.agentops.model.ModelPort;
import com.agentops.model.OpenAiChatClient;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 只返回脱敏配置；连接检测明确触发真实请求。 */
@RestController
@RequestMapping("/api/runtime")
public class RuntimeController {
    private final AgentProperties props;
    private final OpenAiChatClient chat;
    private final EmbeddingRouter embedding;
    private final com.agentops.model.ModelSettingsService settings;

    public RuntimeController(AgentProperties props, OpenAiChatClient chat, EmbeddingRouter embedding,
                             com.agentops.model.ModelSettingsService settings) {
        this.props=props; this.chat=chat; this.embedding=embedding;
        this.settings=settings;
    }

    @GetMapping
    public Map<String,Object> status(@RequestHeader("X-Tenant-Id") String tenantId,
                                     @RequestHeader("X-User-Id") String userId) {
        var model=settings.current(ApiController.principal(tenantId,userId));
        return Map.of("provider",model.provider(),"chatModel",model.model(),
                "chatHost",model.isMock()?"演示模式":URI.create(model.url()).getHost(),
                "keyConfigured",!model.key().isBlank(),
                "embeddingProvider",props.model().vectorProvider(),"embeddingModel",props.model().embeddingModel(),
                "embeddingDimensions",props.model().embeddingDimensions());
    }

    @PostMapping("/check")
    public Map<String,Object> check(@RequestHeader("X-Tenant-Id") String tenantId,
                                    @RequestHeader("X-User-Id") String userId) {
        var model=settings.current(ApiController.principal(tenantId,userId));
        var result=new LinkedHashMap<String,Object>();
        if(model.isMock()) {
            result.put("chat",Map.of("ok",false,"message","当前为演示模型"));
        } else {
            try {
                var response=chat.chat(model,List.of(ModelPort.ModelMessage.user("只回答 OK。")),null,16);
                result.put("chat",Map.of("ok",response.complete(),"message",response.complete()?"真实聊天模型已连通":"响应未完成"));
            } catch(RuntimeException e) {
                result.put("chat",Map.of("ok",false,"message","聊天模型连接失败，请检查地址、模型名和密钥"));
            }
        }
        try {
            var vector=embedding.embed("连接检测");
            result.put("embedding",Map.of("ok",true,"message",embedding.modelId()+" · "+vector.length+" 维"));
        } catch(RuntimeException e) {
            result.put("embedding",Map.of("ok",false,"message","向量服务连接失败，请检查 Ollama 和模型配置"));
        }
        return result;
    }
}
