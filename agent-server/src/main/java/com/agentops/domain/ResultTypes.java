package com.agentops.domain;

import com.agentops.domain.AgentDomain.ValidationVerdict;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 结果、校验与用量值对象（文档 17 / 19 节）。
 */
public final class ResultTypes {

    private ResultTypes() {
    }

    /** 证据引用：来源不能获得指令权限（文档 28.1）。 */
    public record EvidenceRef(String sourceType, String sourceId, String sourceVersion, String snippet) {
    }

    /** 产物引用。 */
    public record ArtifactRef(String objectId, String originalName, String mimeType,
                              long byteSize, String sha256) {
    }

    /** 候选答案：模型说"完成了"只产生候选，仍需校验 + FINALIZE 原子提交（文档 1.2）。 */
    public record CandidateAnswer(UUID revisionId, String markdown,
                                  List<EvidenceRef> evidence, List<ArtifactRef> artifacts,
                                  Map<String, Object> businessFacts) {

        public static CandidateAnswer of(String revisionId, String markdown) {
            return new CandidateAnswer(UUID.fromString(revisionId), markdown,
                    List.of(), List.of(), Map.of());
        }
    }

    /** 校验报告（文档 19.3）。 */
    public record ValidationReport(ValidationVerdict verdict, String validatorVersion,
                                   List<String> errorCodes, String safeFeedback,
                                   List<String> verifiedArtifactIds) {

        public static ValidationReport pass(String validatorVersion, List<String> verifiedArtifacts) {
            return new ValidationReport(ValidationVerdict.PASS, validatorVersion,
                    List.of(), "", verifiedArtifacts);
        }

        public static ValidationReport repairable(String validatorVersion, List<String> errors, String feedback) {
            return new ValidationReport(ValidationVerdict.REPAIRABLE, validatorVersion, errors, feedback, List.of());
        }

        public static ValidationReport needUserInput(String validatorVersion, List<String> errors, String feedback) {
            return new ValidationReport(ValidationVerdict.NEED_USER_INPUT, validatorVersion, errors, feedback, List.of());
        }

        public static ValidationReport fatal(String validatorVersion, List<String> errors, String feedback) {
            return new ValidationReport(ValidationVerdict.FATAL, validatorVersion, errors, feedback, List.of());
        }
    }

    /** 单次调用用量（文档 17.2：用量未知时保守处理）。 */
    public record Usage(long inputTokens, long outputTokens, long costMicros, boolean finalUsageKnown) {

        public static Usage unknown(long conservativeTokens, long conservativeCost) {
            return new Usage(conservativeTokens, 0, conservativeCost, false);
        }

        public static Usage of(long inputTokens, long outputTokens) {
            // 简单估算：不做精确计费，仅按 token 数折算（价格版本可配置后替换）
            return new Usage(inputTokens, outputTokens, 0, true);
        }

        public long totalTokens() {
            return inputTokens + outputTokens;
        }
    }
}
