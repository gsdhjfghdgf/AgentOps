package com.agentops.model;

import com.agentops.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 确定性伪向量（开发/测试用）：同一文本永远得到同一向量，无需外部服务。
 * 仅用于打通链路，不保证语义质量；生产环境配置 provider=openai 使用真实 embedding。
 */
@Component
public class HashEmbeddingModel implements EmbeddingPort {

    private static final Logger log = LoggerFactory.getLogger(HashEmbeddingModel.class);

    private final int dimensions;
    private final boolean mock;

    public HashEmbeddingModel(AgentProperties props) {
        this.dimensions = props.model().embeddingDimensions();
        this.mock = props.model().isMock();
        if (mock) {
            log.info("使用 HashEmbedding（{} 维，确定性伪向量，仅用于开发测试）", dimensions);
        }
    }

    @Override
    public String modelId() {
        return "hash-embedding-v1-" + dimensions;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dimensions];
        if (text == null) return v;
        // 以 1~3 gram 滑窗投入多个哈希槽，让相似文本有一定重叠（伪语义）
        String normalized = text.toLowerCase().replaceAll("\\s+", " ").trim();
        for (int i = 0; i < normalized.length(); i++) {
            for (int n = 1; n <= 3 && i + n <= normalized.length(); n++) {
                String gram = normalized.substring(i, i + n);
                long h = fnv1a64(gram);
                int slot = (int) Math.floorMod(h, dimensions);
                float sign = (h >>> 63) == 1 ? -1f : 1f;
                v[slot] += sign / n;
            }
        }
        // L2 归一化（cosine 等价于内积）
        double norm = 0;
        for (float x : v) norm += x * (double) x;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] / norm);
        }
        return v;
    }

    private long fnv1a64(String s) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        return hash;
    }
}
