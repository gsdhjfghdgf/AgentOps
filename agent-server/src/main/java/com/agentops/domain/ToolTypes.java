package com.agentops.domain;

import com.agentops.domain.AgentDomain.EffectClass;
import com.agentops.domain.AgentDomain.PolicyVerdict;
import com.agentops.domain.AgentDomain.ToolOutcomeStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 工具体系值对象（文档 11 / 15 / 16 节）。
 */
public final class ToolTypes {

    private ToolTypes() {
    }

    /**
     * 工具定义（文档 11.1）：名称唯一、副作用类别由开发者注册，
     * 权限、超时与返回上限都在注册时声明。
     */
    public record ToolSpec(String name, String version, String description,
                           Map<String, PropertySpec> inputSchema,
                           EffectClass effectClass, boolean requiresApproval,
                           Duration timeout, int maxResultBytes) {

        /** JSON Schema 属性描述（简化版，足够驱动 OpenAI 兼容协议）。 */
        public record PropertySpec(String type, String description, boolean required) {
            public static PropertySpec req(String type, String description) {
                return new PropertySpec(type, description, true);
            }

            public static PropertySpec opt(String type, String description) {
                return new PropertySpec(type, description, false);
            }
        }

        /** 转换为 OpenAI 兼容 tools 参数的 JSON 结构。 */
        public Map<String, Object> toOpenAiFunction() {
            var properties = new java.util.LinkedHashMap<String, Object>();
            var required = new java.util.ArrayList<String>();
            inputSchema.forEach((k, v) -> {
                var prop = new java.util.LinkedHashMap<String, Object>();
                prop.put("type", v.type());
                if (v.description() != null) prop.put("description", v.description());
                properties.put(k, prop);
                if (v.required()) required.add(k);
            });
            var schema = new java.util.LinkedHashMap<String, Object>();
            schema.put("type", "object");
            schema.put("properties", properties);
            if (!required.isEmpty()) schema.put("required", required);
            var fn = new java.util.LinkedHashMap<String, Object>();
            fn.put("name", name);
            fn.put("description", description);
            fn.put("parameters", schema);
            return fn;
        }
    }

    /**
     * 工具调用意图（文档 16.2）：step_id/effect_key 在接受完整模型意图后
     * 生成并持久化，恢复时保持不变；不含每次变化的 worker_epoch/attempt_id。
     */
    public record ToolIntent(UUID stepId, String modelCallId, String toolName, String toolVersion,
                             Map<String, Object> arguments, String argumentsHash,
                             String actionDigest, String effectKey) {
    }

    /** 模型返回的一条原始工具调用请求。 */
    public record ToolCallRequest(String callId, String toolName, Map<String, Object> arguments) {
    }

    /** 工具执行结果。 */
    public record ToolResult(ToolOutcomeStatus status, String modelSafeText,
                             String providerReference, String artifactId, String errorCode) {

        public static ToolResult success(String text) {
            return new ToolResult(ToolOutcomeStatus.SUCCEEDED, text, null, null, null);
        }

        public static ToolResult successWithArtifact(String text, String artifactId) {
            return new ToolResult(ToolOutcomeStatus.SUCCEEDED, text, null, artifactId, null);
        }

        public static ToolResult failed(String errorCode, String safeMessage) {
            return new ToolResult(ToolOutcomeStatus.FAILED, safeMessage, null, null, errorCode);
        }

        public static ToolResult unknown(String providerReference) {
            return new ToolResult(ToolOutcomeStatus.UNKNOWN, "外部结果未知，等待核对",
                    providerReference, null, "OUTCOME_UNKNOWN");
        }
    }

    /** 策略决策（文档 15.1 三道检查的结论）。 */
    public record PolicyDecision(PolicyVerdict verdict, String reasonCode,
                                 String actionDigest, Instant authorizationExpiresAt) {

        public static PolicyDecision allow(String digest) {
            return new PolicyDecision(PolicyVerdict.ALLOW, null, digest, null);
        }

        public static PolicyDecision deny(String reasonCode) {
            return new PolicyDecision(PolicyVerdict.DENY, reasonCode, null, null);
        }

        public static PolicyDecision requireApproval(String digest) {
            return new PolicyDecision(PolicyVerdict.REQUIRE_APPROVAL, "APPROVAL_REQUIRED", digest, null);
        }
    }

    /** 批准记录（文档 15.2：绑定 approver、digest、有效期、消费状态）。 */
    public record Approval(UUID approvalId, UUID runId, UUID stepId, String callId,
                           String toolName, String actionDigest, String status,
                           String reason, Instant expiresAt, Instant consumedAt) {
    }
}
