package com.agentops.model;

import org.springframework.stereotype.Component;

/**
 * Embedding 路由：provider=mock 用确定性伪向量，provider=openai 用真实 embedding。
 * modelId 写入 embedding_model 列；召回时必须校验该列一致（文档 6.3 / 8.3）。
 */
@Component
public class EmbeddingRouter implements EmbeddingPort {

    private final HashEmbeddingModel hash;
    private final OpenAiEmbeddingModel openAi;
    private final OllamaEmbeddingModel ollama;
    private final com.agentops.config.AgentProperties props;
    private final EmbeddingCache cache=new EmbeddingCache(256,java.time.Duration.ofMinutes(10));

    public EmbeddingRouter(HashEmbeddingModel hash, OpenAiEmbeddingModel openAi,
                           OllamaEmbeddingModel ollama, com.agentops.config.AgentProperties props) {
        this.hash = hash;
        this.openAi = openAi;
        this.ollama = ollama;
        this.props = props;
    }

    private EmbeddingPort active() {
        return switch (props.model().vectorProvider().toLowerCase(java.util.Locale.ROOT)) {
            case "ollama" -> ollama;
            case "openai" -> openAi;
            case "mock" -> hash;
            default -> throw new IllegalStateException("不支持的向量服务配置");
        };
    }

    @Override
    public String modelId() {
        return active().modelId();
    }

    @Override
    public float[] embed(String text) {
        var provider=active();
        String material=props.model().vectorProvider()+"\n"+provider.modelId()+"\n"+text;
        try {
            String key=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return cache.get(key,()->provider.embed(text));
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
