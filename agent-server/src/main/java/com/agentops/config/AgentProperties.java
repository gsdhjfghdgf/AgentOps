package com.agentops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 运行配置（对应 application.yml 的 agent.*）。
 * 所有数值都是初始示例，需按业务与压测调整（文档顶部数值说明）。
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(Model model, Budget budget, Lease lease, Scheduler scheduler,
                              Context context, Tools tools, Artifacts artifacts, Events events) {

    /** 模型接入：mock 或 openai（OpenAI 兼容协议）。 */
    public record Model(String provider, String baseUrl, String apiKey, String chatModel,
                        String embeddingModel, int embeddingDimensions,
                        int timeoutSeconds, int maxRetries, String apiUrl,
                        String embeddingProvider, String embeddingBaseUrl, String embeddingApiKey) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Model {}

        public Model(String provider, String baseUrl, String apiKey, String chatModel,
                     String embeddingModel, int embeddingDimensions, int timeoutSeconds, int maxRetries) {
            this(provider, baseUrl, apiKey, chatModel, embeddingModel, embeddingDimensions,
                    timeoutSeconds, maxRetries, null, null, null, null);
        }

        public String chatEndpoint() {
            return apiUrl != null && !apiUrl.isBlank() ? apiUrl : endpoint(baseUrl, "chat/completions");
        }

        public String vectorProvider() {
            return embeddingProvider == null || embeddingProvider.isBlank()
                    ? (isMock() ? "mock" : "openai") : embeddingProvider;
        }

        public static String endpoint(String base, String path) {
            if (base == null || base.isBlank()) throw new IllegalStateException("模型服务地址未配置");
            String root = base.replaceAll("/+$", "");
            return root + (root.endsWith("/v1") ? "/" : "/v1/") + path;
        }
        public boolean isMock() {
            return provider == null || provider.isBlank() || "mock".equalsIgnoreCase(provider);
        }
    }

    /** 文档 17.1 初始配额。 */
    public record Budget(int maxModelAttempts, int maxToolAttempts, long maxTotalTokens,
                         int maxOutputTokensPerCall, long maxCostMicros, String currency,
                         int runWallTimeoutMinutes) {
    }

    /** 文档 16.3 Worker 租约。 */
    public record Lease(int durationSeconds, long renewIntervalMillis) {
    }

    public record Scheduler(long pollIntervalMillis, long scanIntervalMillis, int maxConcurrentRuns) {
    }

    /** 文档 7 上下文工程。 */
    public record Context(int fullTailMessages, int summaryTriggerMessages,
                          int maxHistoryTokens, int recallMemories) {
    }

    public record Tools(int defaultTimeoutSeconds, int maxResultBytes) {
    }

    public record Artifacts(String dir, long maxUploadBytes) {
    }

    public record Events(long pollIntervalMillis, int heartbeatSeconds) {
    }
}
