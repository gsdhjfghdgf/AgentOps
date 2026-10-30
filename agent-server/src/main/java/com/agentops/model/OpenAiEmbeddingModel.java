package com.agentops.model;

import com.agentops.config.AgentProperties;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容 embedding 适配器：配置 provider=openai 时生效。
 */
@Component
public class OpenAiEmbeddingModel implements EmbeddingPort {

    private final OpenAiChatClient client;
    private final AgentProperties props;

    public OpenAiEmbeddingModel(OpenAiChatClient client, AgentProperties props) {
        this.client = client;
        this.props = props;
    }

    public boolean active() {
        return "openai".equalsIgnoreCase(props.model().vectorProvider());
    }

    @Override
    public String modelId() {
        return props.model().embeddingModel() + "@" + props.model().embeddingDimensions();
    }

    @Override
    public float[] embed(String text) {
        if (!active()) {
            throw new IllegalStateException("OpenAI embedding 未启用（provider=mock）");
        }
        return client.embed(props.model().embeddingModel(), text, props.model().embeddingDimensions());
    }
}
