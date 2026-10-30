package com.agentops.model;

/**
 * Embedding 端口：记忆与知识检索的向量化（文档 6 / 8.3 / 30 节）。
 * 不同模型/维度的向量不能混用——调用方必须同时校验 embedding_model 与维度。
 */
public interface EmbeddingPort {

    /** 模型标识，写入 embedding_model 列。 */
    String modelId();

    /** 向量化文本。 */
    float[] embed(String text);

    /** 转换为 pgvector 字面量：[1,2,3]。 */
    default String toLiteral(float[] vector) {
        var sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
