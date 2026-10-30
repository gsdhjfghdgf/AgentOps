package com.agentops.model;

import com.agentops.config.AgentProperties;
import com.agentops.infra.Json;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** 独立的本地向量服务；不向 Ollama 发送聊天服务密钥，不静默截断文本或填充向量。 */
@Component
public class OllamaEmbeddingModel implements EmbeddingPort {
    private final RestClient rest;
    private final AgentProperties props;
    private final Json json;

    public OllamaEmbeddingModel(RestClient rest, AgentProperties props, Json json) {
        this.rest = rest;
        this.props = props;
        this.json = json;
    }

    @Override
    public String modelId() {
        return "ollama:" + props.model().embeddingModel() + "@" + props.model().embeddingDimensions();
    }

    @Override
    public float[] embed(String text) {
        String base = props.model().embeddingBaseUrl();
        if (base == null || base.isBlank()) throw new IllegalStateException("Ollama 服务地址未配置");
        try {
            String response = rest.post().uri(base.replaceAll("/+$", "") + "/api/embed")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(json.write(Map.of("model", props.model().embeddingModel(),
                            "input", List.of(text), "truncate", false)))
                    .retrieve().body(String.class);
            var vector = json.tree(response).path("embeddings").path(0);
            int dimensions = props.model().embeddingDimensions();
            if (!vector.isArray() || vector.size() != dimensions || dimensions != 1024) {
                throw new IllegalStateException("Ollama 向量维度不匹配：数据库要求 1024 维，服务返回 " + vector.size());
            }
            float[] result = new float[dimensions];
            double norm = 0;
            for (int i = 0; i < dimensions; i++) {
                if (!vector.get(i).isNumber()) throw new IllegalStateException("Ollama 返回无效向量");
                result[i] = (float) vector.get(i).asDouble();
                if (!Float.isFinite(result[i])) throw new IllegalStateException("Ollama 返回无效向量");
                norm += (double) result[i] * result[i];
            }
            if (norm == 0) throw new IllegalStateException("Ollama 返回零向量");
            return result;
        } catch (org.springframework.web.client.RestClientException e) {
            throw new IllegalStateException("Ollama 向量服务不可用，请检查服务是否启动以及模型是否已安装", e);
        }
    }
}
