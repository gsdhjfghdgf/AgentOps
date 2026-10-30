package com.agentops.validation;

import com.agentops.domain.AgentDomain.ValidationVerdict;
import com.agentops.domain.Primitives.CompletionContract;
import com.agentops.domain.Primitives.RunSnapshot;
import com.agentops.domain.ResultTypes.CandidateAnswer;
import com.agentops.domain.ResultTypes.ValidationReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 结果校验器（文档 19.3 三层校验）：
 * 1. 结构校验：候选答案格式完整、引用有效、无空洞；
 * 2. 业务校验：完成契约中的必需字段/指标存在且一致；
 * 3. 产物证据：契约要求产物时，产物必须真实存在（引用数据库登记）。
 *
 * 结论：PASS / REPAIRABLE（可自动修复，反馈安全文本回模型）/
 *       NEED_USER_INPUT（缺信息）/ FATAL（不可修复）。
 *
 * 模型自称"完成"只产生候选，仍需本校验 + FINALIZE 原子提交（文档 1.2）。
 */
@Service
public class ResultValidator {

    private static final Logger log = LoggerFactory.getLogger(ResultValidator.class);

    private static final String VERSION = "contract-validator-1.0";

    public ValidationReport validate(RunSnapshot run, CandidateAnswer candidate) {
        var errors = new ArrayList<String>();
        var contract = run.contract() == null
                ? CompletionContract.defaults("generic") : run.contract();

        // ---- 第一层：结构校验 ----
        String markdown = candidate.markdown() == null ? "" : candidate.markdown();
        if (markdown.isBlank()) {
            return ValidationReport.needUserInput(VERSION, List.of("EMPTY_ANSWER"),
                    "答案为空。请补充处理结果，或说明缺少什么信息。");
        }
        Object minimum = contract.requirements().get("minAnswerChars");
        if (minimum instanceof Number count && markdown.length() < count.intValue()) {
            errors.add("ANSWER_TOO_SHORT");
        }
        // 检测明显的未完成占位
        if (markdown.contains("TODO") || markdown.contains("待补充") || markdown.contains("???")) {
            errors.add("PLACEHOLDER_LEFT");
        }

        // ---- 第二层：业务校验（完成契约，文档 19.1）----
        if (contract.evidenceRequired()) {
            boolean hasEvidence = !candidate.evidence().isEmpty()
                    || markdown.contains("来源") || markdown.contains("依据")
                    || markdown.contains("查询结果") || markdown.contains("统计");
            if (!hasEvidence) {
                errors.add("EVIDENCE_MISSING");
            }
        }
        // 契约要求的业务字段：requirements 中 requiredFacts 列出的字段必须在答案中出现
        Object requiredFacts = contract.requirements().get("requiredFacts");
        if (requiredFacts instanceof List<?> facts) {
            for (var fact : facts) {
                if (!markdown.contains(fact.toString())) {
                    errors.add("FACT_MISSING:" + fact);
                }
            }
        }
        // 数字一致性抽查：若契约给了 expectedNumbers（字段→值），答案中的对应数字必须一致
        Object expectedNumbers = contract.requirements().get("expectedNumbers");
        if (expectedNumbers instanceof Map<?, ?> expected) {
            for (var entry : expected.entrySet()) {
                String value = String.valueOf(entry.getValue());
                if (!markdown.contains(value)) {
                    errors.add("NUMBER_MISMATCH:" + entry.getKey());
                }
            }
        }

        // ---- 第三层：产物证据（契约要求产物必须真实登记）----
        var verified = new ArrayList<String>();
        List<String> requiredKinds = contract.requiredArtifactKinds() == null
                ? List.of() : contract.requiredArtifactKinds();
        if (!requiredKinds.isEmpty()) {
            // 候选答案声明的产物必须与数据库登记一致（这里通过 engine 传入的已登记产物核对）
            // 简化：答案中提到的"对象ID"必须出现在已登记产物集合中（由 engine 传入 verifiedArtifacts）
            // 当前 engine 在 commitSuccess 时从 tool_calls.result_ref 收集，此层只检查数量
            if (candidate.artifacts().isEmpty()) {
                errors.add("ARTIFACT_REQUIRED_BUT_MISSING");
            }
        }

        // ---- 结论 ----
        if (errors.isEmpty()) {
            log.debug("校验通过 run={} revision={}", run.key().runId(), candidate.revisionId());
            return ValidationReport.pass(VERSION, verified);
        }

        // 分级：缺信息 → NEED_USER_INPUT；可修正 → REPAIRABLE；其余 FATAL
        Set<String> userInputErrors = Set.of("EMPTY_ANSWER");
        boolean needUser = errors.stream().anyMatch(userInputErrors::contains);
        boolean fatal = errors.stream().anyMatch(e ->
                e.startsWith("ARTIFACT_REQUIRED_BUT_MISSING") && contract.evidenceRequired());

        String feedback = buildSafeFeedback(errors);
        if (needUser) {
            return ValidationReport.needUserInput(VERSION, errors, feedback);
        }
        if (fatal) {
            log.warn("校验致命失败 run={} errors={}", run.key().runId(), errors);
            return ValidationReport.fatal(VERSION, errors, feedback);
        }
        log.info("校验未通过（可修复）run={} errors={}", run.key().runId(), errors);
        return ValidationReport.repairable(VERSION, errors, feedback);
    }

    /** 安全反馈：只包含可修正信息，不泄露内部状态或堆栈（文档 19.3）。 */
    private String buildSafeFeedback(List<String> errors) {
        var sb = new StringBuilder("上次输出未通过自动校验，请按以下要求修正后重新输出完整答案：\n");
        for (var e : errors) {
            if (e.startsWith("FACT_MISSING:")) {
                sb.append("- 答案中缺少必需信息「").append(e.substring("FACT_MISSING:".length()))
                        .append("」，请从工具结果中提取并明确写出。\n");
            } else if (e.startsWith("NUMBER_MISMATCH:")) {
                sb.append("- 指标「").append(e.substring("NUMBER_MISMATCH:".length()))
                        .append("」的数值与工具返回不一致，请使用工具返回的真实数值。\n");
            } else if (e.equals("ANSWER_TOO_SHORT")) {
                sb.append("- 答案过于简短，请补充关键结论与依据。\n");
            } else if (e.equals("PLACEHOLDER_LEFT")) {
                sb.append("- 答案中残留 TODO/待补充占位符，请替换为实际内容。\n");
            } else if (e.equals("EVIDENCE_MISSING")) {
                sb.append("- 答案缺少依据说明，请引用工具结果或知识来源。\n");
            } else if (e.equals("ARTIFACT_REQUIRED_BUT_MISSING")) {
                sb.append("- 本任务要求交付文件产物，但未检测到产物，请先调用报告生成工具。\n");
            } else {
                sb.append("- ").append(e).append('\n');
            }
        }
        return sb.toString();
    }

    /** 校验器版本（评测与回归对比用，文档 23）。 */
    public static String version() {
        return VERSION;
    }

    /** 供测试/评测：直接构造 PASS 报告。 */
    public static ValidationReport passReport(List<String> artifactIds) {
        return ValidationReport.pass(VERSION, artifactIds == null ? List.of() : artifactIds);
    }

    /** 校验候选答案的 revisionId 是否有效 UUID（防格式错误）。 */
    public static boolean validRevision(UUID id) {
        return id != null;
    }

    /** 保留：契约版本比对（契约修订后旧报告失效，文档 29.3）。 */
    public boolean contractStillValid(RunSnapshot run, long reportContractVersion) {
        return run.contract() != null && run.contract().contractVersion() == reportContractVersion;
    }
}
