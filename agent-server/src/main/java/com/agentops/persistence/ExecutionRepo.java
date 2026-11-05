package com.agentops.persistence;

import com.agentops.domain.AgentDomain.EffectClass;
import com.agentops.domain.AgentDomain.StepKind;
import com.agentops.domain.AgentDomain.ToolOutcomeStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 执行链路仓储：run_steps / tool_calls / operation_attempts / validation_reports（文档 8.1）。
 * tool_calls.effect_key 全局唯一约束保证同 owner 不会重复登记同一业务副作用（文档 16.2）。
 */
@Repository
public class ExecutionRepo {

    private final JdbcTemplate jdbc;

    public ExecutionRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== run_steps ====================

    public record StepRow(UUID stepId, int ordinal, StepKind kind, String status,
                          String effectKey, String description) {
    }

    public void insertStep(UUID tenantId, UUID userId, UUID sessionId, UUID runId,
                           StepRow step) {
        jdbc.update("""
                INSERT INTO run_steps (tenant_id, user_id, session_id, run_id, step_id,
                                       ordinal, kind, status, effect_key, description)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, tenantId, userId, sessionId, runId, step.stepId(), step.ordinal(),
                step.kind().name().toLowerCase(), "pending", step.effectKey(), step.description());
    }

    public void updateStepStatus(UUID tenantId, UUID runId, UUID stepId, String status) {
        jdbc.update("""
                UPDATE run_steps SET status=?, updated_at=now()
                WHERE tenant_id=? AND run_id=? AND step_id=?
                """, status, tenantId, runId, stepId);
    }

    public List<StepRow> listSteps(UUID tenantId, UUID runId) {
        return jdbc.query("""
                SELECT step_id, ordinal, kind, status, effect_key, description
                FROM run_steps WHERE tenant_id=? AND run_id=? ORDER BY ordinal
                """, Jdbc.reader(rs -> new StepRow(Jdbc.uuid(rs, "step_id"), rs.getInt("ordinal"),
                StepKind.valueOf(rs.getString("kind").toUpperCase()), rs.getString("status"),
                rs.getString("effect_key"), rs.getString("description"))), tenantId, runId);
    }

    // ==================== tool_calls ====================

    public record ToolCallRow(String callId, UUID stepId, String toolName, String toolVersion,
                              String argumentsJson, String argumentsHash, String actionDigest,
                              String effectKey, EffectClass effectClass, String status,
                              String resultRef, String errorCode) {
    }

    /** 登记完整调用意图（执行前，T3 事务的一部分）。 */
    public void insertToolCall(UUID tenantId, UUID userId, UUID sessionId, UUID runId, ToolCallRow row) {
        jdbc.update("""
                INSERT INTO tool_calls (tenant_id, user_id, session_id, run_id, step_id, call_id,
                                        tool_name, tool_version, arguments, arguments_hash,
                                        action_digest, effect_key, effect_class, status)
                VALUES (?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)
                """, tenantId, userId, sessionId, runId, row.stepId(), row.callId(),
                row.toolName(), row.toolVersion(), row.argumentsJson(), row.argumentsHash(),
                row.actionDigest(), row.effectKey(), row.effectClass().name().toLowerCase(), row.status());
    }

    /** effect_key 已登记过（幂等冲突：模型换了 call_id 不一定代表新业务，文档 16.2）。 */
    public Optional<ToolCallRow> findByEffectKey(UUID tenantId, UUID userId, String effectKey) {
        var rows = jdbc.query("""
                SELECT call_id, step_id, tool_name, tool_version, arguments::text, arguments_hash,
                       action_digest, effect_key, effect_class, status, result_ref, error_code
                FROM tool_calls WHERE tenant_id=? AND user_id=? AND effect_key=?
                """, toolCallMapper(), tenantId, userId, effectKey);
        return rows.stream().findFirst();
    }

    public Optional<ToolCallRow> findToolCall(UUID tenantId, UUID runId, String callId) {
        var rows = jdbc.query("""
                SELECT call_id, step_id, tool_name, tool_version, arguments::text, arguments_hash,
                       action_digest, effect_key, effect_class, status, result_ref, error_code
                FROM tool_calls WHERE tenant_id=? AND run_id=? AND call_id=?
                """, toolCallMapper(), tenantId, runId, callId);
        return rows.stream().findFirst();
    }

    public List<ToolCallRow> listToolCalls(UUID tenantId, UUID runId) {
        return jdbc.query("""
                SELECT call_id, step_id, tool_name, tool_version, arguments::text, arguments_hash,
                       action_digest, effect_key, effect_class, status, result_ref, error_code
                FROM tool_calls WHERE tenant_id=? AND run_id=? ORDER BY created_at
                """, toolCallMapper(), tenantId, runId);
    }

    public void updateToolCallStatus(UUID tenantId, UUID runId, String callId,
                                     String status, String resultRef, String errorCode) {
        jdbc.update("""
                UPDATE tool_calls
                SET status=?, result_ref=?, error_code=?,
                    finished_at=CASE WHEN ? IN ('succeeded','failed','cancelled') THEN now() ELSE finished_at END
                WHERE tenant_id=? AND run_id=? AND call_id=?
                """, status, resultRef, errorCode, status, tenantId, runId, callId);
    }

    /**
     * 换绑 call_id（effect_key 幂等命中但模型生成了新 call_id 时）：
     * tool_calls 行必须指向当前批次使用的 call_id，否则后续按 call_id 的状态更新会命中 0 行，
     * 状态永远停在 recorded，幂等重放判定失效。
     */
    public void rebindToolCallIds(UUID tenantId, UUID runId, String oldCallId,
                                  String newCallId, UUID newStepId) {
        jdbc.update("""
                UPDATE tool_calls SET call_id=?, step_id=?
                WHERE tenant_id=? AND run_id=? AND call_id=?
                """, newCallId, newStepId, tenantId, runId, oldCallId);
    }

    private org.springframework.jdbc.core.RowMapper<ToolCallRow> toolCallMapper() {
        return Jdbc.reader(rs -> new ToolCallRow(rs.getString("call_id"), Jdbc.uuid(rs, "step_id"),
                rs.getString("tool_name"), rs.getString("tool_version"), rs.getString("arguments"),
                rs.getString("arguments_hash"), rs.getString("action_digest"), rs.getString("effect_key"),
                EffectClass.valueOf(rs.getString("effect_class").toUpperCase()), rs.getString("status"),
                rs.getString("result_ref"), rs.getString("error_code")));
    }

    // ==================== operation_attempts ====================

    public record AttemptRow(UUID attemptId, UUID stepId, int attemptNo, StepKind kind, long epoch,
                             String state, String providerRef, String errorCode, boolean outcomeUnknown,
                             long reservedTokens, long usedTokens) {
    }

    public void insertAttempt(UUID tenantId, UUID userId, UUID sessionId, UUID runId, AttemptRow row) {
        jdbc.update("""
                INSERT INTO operation_attempts (tenant_id, user_id, session_id, run_id, step_id,
                                                attempt_id, attempt_no, kind, epoch, state,
                                                reserved_tokens)
                VALUES (?,?,?,?,?,?,?,?,?,'reserved',?)
                """, tenantId, userId, sessionId, runId, row.stepId(), row.attemptId(),
                row.attemptNo(), row.kind().name().toLowerCase(), row.epoch(), row.reservedTokens());
    }

    public boolean interruptModel(UUID tenantId,UUID runId,UUID attemptId,long estimatedTokens){
        return jdbc.update("""
                UPDATE operation_attempts SET state='failed',error_code='MODEL_INTERRUPTED_USAGE_UNKNOWN',
                  outcome_unknown=true,used_tokens=?,usage_known=false,finished_at=now()
                WHERE tenant_id=? AND run_id=? AND attempt_id=? AND kind='model' AND state IN ('reserved','running')
                """,estimatedTokens,tenantId,runId,attemptId)>0;
    }
    public void markAttemptRunning(UUID tenantId, UUID runId, UUID attemptId) {
        jdbc.update("""
                UPDATE operation_attempts
                SET state='running', started_at=COALESCE(started_at, now())
                WHERE tenant_id=? AND run_id=? AND attempt_id=? AND state='reserved'
                """, tenantId, runId, attemptId);
    }

    /** Claim a known failed model result once, before settling its reported usage. */
    public boolean finishKnownModelFailure(UUID tenantId, UUID runId, UUID attemptId, String errorCode, long usedTokens) {
        return jdbc.update("""
                UPDATE operation_attempts
                SET state='failed',error_code=?,outcome_unknown=false,used_tokens=?,usage_known=true,finished_at=now()
                WHERE tenant_id=? AND run_id=? AND attempt_id=? AND kind='model'
                  AND state IN ('reserved','running')
                """,errorCode,usedTokens,tenantId,runId,attemptId)>0;
    }

    public void finishAttempt(UUID tenantId, UUID runId, UUID attemptId, String state,
                              String providerRef, String errorCode, boolean outcomeUnknown,
                              long usedTokens, boolean usageKnown) {
        jdbc.update("""
                UPDATE operation_attempts
                SET state=?, provider_ref=?, error_code=?, outcome_unknown=?,
                    used_tokens=?, usage_known=?, finished_at=now()
                WHERE tenant_id=? AND run_id=? AND attempt_id=?
                """, state, providerRef, errorCode, outcomeUnknown, usedTokens, usageKnown,
                tenantId, runId, attemptId);
    }

    /** 同一步骤未完成的尝试数（文档 8.1 限制并发尝试）。 */
    public int countOpenAttempts(UUID tenantId, UUID runId, UUID stepId) {
        var n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM operation_attempts
                WHERE tenant_id=? AND run_id=? AND step_id=?
                  AND state IN ('pending','reserved','running','unknown')
                """, Integer.class, tenantId, runId, stepId);
        return n == null ? 0 : n;
    }
    /** Only consecutive failures since the last successful response or explicit resume affect retry backoff. */
    public int consecutiveModelFailures(UUID tenant,UUID run) {
        var states=jdbc.queryForList("""
                SELECT state,error_code FROM operation_attempts
                WHERE tenant_id=? AND run_id=? AND kind='model' AND state IN ('failed','unknown','succeeded')
                  AND created_at>COALESCE((SELECT max(created_at) FROM run_events
                      WHERE tenant_id=? AND run_id=? AND type='run.resumed'),'-infinity'::timestamptz)
                ORDER BY created_at DESC,attempt_no DESC,attempt_id DESC LIMIT 3
                """,tenant,run,tenant,run);
        int failures=0;
        for(var state:states) {
            if("succeeded".equals(state.get("state")) || "MODEL_INTERRUPTED_USAGE_UNKNOWN".equals(state.get("error_code")))break;
            failures++;
        }
        return failures;
    }

    /** 按类别统计已发生的尝试次数（预算：模型/工具实际尝试次数）。 */
    public int countAttemptsByKind(UUID tenantId, UUID runId, StepKind kind) {
        var n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM operation_attempts
                WHERE tenant_id=? AND run_id=? AND kind=?
                """, Integer.class, tenantId, runId, kind.name().toLowerCase());
        return n == null ? 0 : n;
    }

    public List<AttemptRow> listOpenUnknown(UUID tenantId, UUID runId) {
        return jdbc.query("""
                SELECT attempt_id, step_id, attempt_no, kind, epoch, state, provider_ref,
                       error_code, outcome_unknown, reserved_tokens, used_tokens
                FROM operation_attempts
                WHERE tenant_id=? AND run_id=? AND state='unknown'
                """, attemptMapper(), tenantId, runId);
    }

    private org.springframework.jdbc.core.RowMapper<AttemptRow> attemptMapper() {
        return Jdbc.reader(rs -> new AttemptRow(Jdbc.uuid(rs, "attempt_id"), Jdbc.uuid(rs, "step_id"),
                rs.getInt("attempt_no"), StepKind.valueOf(rs.getString("kind").toUpperCase()),
                rs.getLong("epoch"), rs.getString("state"), rs.getString("provider_ref"),
                rs.getString("error_code"), rs.getBoolean("outcome_unknown"),
                rs.getLong("reserved_tokens"), rs.getLong("used_tokens")));
    }

    // ==================== validation_reports ====================

    public void insertValidationReport(UUID tenantId, UUID userId, UUID sessionId, UUID runId,
                                       UUID id, UUID candidateRevision, long contractVersion,
                                       String validatorVersion, String verdict, String errorsJson,
                                       String safeFeedback, String verifiedArtifactsJson) {
        jdbc.update("""
                INSERT INTO validation_reports (tenant_id, user_id, session_id, run_id, id,
                                                candidate_revision, contract_version, validator_version,
                                                verdict, errors, safe_feedback, verified_artifacts)
                VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb)
                ON CONFLICT (tenant_id, run_id, candidate_revision, validator_version) DO NOTHING
                """,
                tenantId, userId, sessionId, runId, id, candidateRevision, contractVersion,
                validatorVersion, verdict, errorsJson, safeFeedback, verifiedArtifactsJson);
    }

    public record ValidationRow(UUID id, String verdict, String errorsJson, String feedback) {
    }

    public Optional<ValidationRow> findLatestValidation(UUID tenantId, UUID runId) {
        var rows = jdbc.query("""
                SELECT id, verdict, errors::text, safe_feedback
                FROM validation_reports WHERE tenant_id=? AND run_id=?
                ORDER BY created_at DESC LIMIT 1
                """, Jdbc.reader(rs -> new ValidationRow(Jdbc.uuid(rs, "id"), rs.getString("verdict"),
                        rs.getString("errors"), rs.getString("safe_feedback"))), tenantId, runId);
        return rows.stream().findFirst();
    }
}
