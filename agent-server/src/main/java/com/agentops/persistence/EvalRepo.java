package com.agentops.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 评测仓储（文档 26.3：eval_suites / eval_cases / eval_trials / eval_grades）。 */
@Repository
public class EvalRepo {

    private final JdbcTemplate jdbc;

    public EvalRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== suites / cases ====================

    public UUID insertSuite(UUID tenantId, String name, String suiteVersion, String description) {
        var existing = jdbc.query("""
                SELECT id FROM eval_suites WHERE tenant_id=? AND name=? AND suite_version=?
                """, Jdbc.reader(rs -> Jdbc.uuid(rs, "id")), tenantId, name, suiteVersion);
        if (!existing.isEmpty()) return existing.get(0);
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO eval_suites (id, tenant_id, name, suite_version, description)
                VALUES (?,?,?,?,?)
                """, id, tenantId, name, suiteVersion, description);
        return id;
    }

    public UUID insertCase(UUID suiteId, String taskId, String category, String input,
                           String initialEnvJson, String allowedToolsJson, String budgetJson,
                           String contractJson, String gradingJson, boolean isRegression) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO eval_cases (id, suite_id, task_id, category, input, initial_env,
                                        allowed_tools, budget, completion_contract, grading, is_regression)
                VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?)
                """, id, suiteId, taskId, category, input, initialEnvJson, allowedToolsJson,
                budgetJson, contractJson, gradingJson, isRegression);
        return id;
    }

    public record CaseRow(UUID id, UUID suiteId, String taskId, String category, String input,
                          String contractJson, String gradingJson, boolean isRegression, String allowedToolsJson) {
    }

    public List<CaseRow> listCases(UUID suiteId) {
        return jdbc.query("""
                SELECT id, suite_id, task_id, category, input, completion_contract::text,
                       grading::text, is_regression, allowed_tools::text
                FROM eval_cases WHERE suite_id=? ORDER BY task_id
                """, Jdbc.reader(rs -> new CaseRow(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "suite_id"),
                rs.getString("task_id"), rs.getString("category"), rs.getString("input"),
                rs.getString("completion_contract"), rs.getString("grading"),
                rs.getBoolean("is_regression"),rs.getString("allowed_tools"))), suiteId);
    }

    // ==================== trials / grades ====================

    /** 下一个可用 trial_no（同 case 重复运行时递增，避免唯一键冲突）。 */
    public int nextTrialNo(UUID caseId) {
        var n = jdbc.queryForObject("""
                SELECT COALESCE(MAX(trial_no),0)+1 FROM eval_trials WHERE case_id=?
                """, Integer.class, caseId);
        return n;
    }

    public UUID insertTrial(UUID suiteId, UUID caseId, int trialNo, String configSnapshotJson,
                            UUID runId, UUID sessionId, String envSeed) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO eval_trials (id, suite_id, case_id, trial_no, config_snapshot,
                                         run_id, session_id, env_seed)
                VALUES (?,?,?,?,?::jsonb,?,?,?)
                """, id, suiteId, caseId, trialNo, configSnapshotJson, runId, sessionId, envSeed);
        return id;
    }

    public void finishTrial(UUID trialId, double score, boolean passed, String errorType,
                            Long durationMs, long usedTokens, String traceSummaryJson) {
        jdbc.update("""
                UPDATE eval_trials
                SET score=?, passed=?, error_type=?, duration_ms=?, used_tokens=?, trace_summary=?::jsonb
                WHERE id=?
                """, score, passed, errorType, durationMs, usedTokens, traceSummaryJson, trialId);
    }

    public void attachRun(UUID trialId, UUID runId) {
        jdbc.update("UPDATE eval_trials SET run_id=? WHERE id=?",runId,trialId);
    }

    public boolean ownsSuite(UUID tenantId,UUID suiteId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM eval_suites WHERE tenant_id=? AND id=?)",
                Boolean.class,tenantId,suiteId));
    }

    public void insertGrade(UUID trialId, String criterion, double score, double maxScore,
                            String grader, String reason) {
        jdbc.update("""
                INSERT INTO eval_grades (trial_id, criterion, score, max_score, grader, reason)
                VALUES (?,?,?,?,?,?)
                """, trialId, criterion, score, maxScore, grader, reason);
    }

    public record TrialSummary(UUID id, UUID caseId, String taskId, int trialNo, double score,
                               boolean passed, String errorType, Long durationMs, long usedTokens, String traceJson) {
    }

    public List<TrialSummary> listTrials(UUID suiteId) {
        return jdbc.query("""
                SELECT t.id, t.case_id, c.task_id, t.trial_no, t.score, t.passed,
                       t.error_type, t.duration_ms,t.used_tokens,t.trace_summary::text
                FROM eval_trials t JOIN eval_cases c ON c.id=t.case_id
                WHERE t.suite_id=? ORDER BY c.task_id, t.trial_no
                """, Jdbc.reader(rs -> new TrialSummary(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "case_id"),
                rs.getString("task_id"), rs.getInt("trial_no"), rs.getDouble("score"),
                rs.getBoolean("passed"), rs.getString("error_type"),
                rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),rs.getLong("used_tokens"),rs.getString("trace_summary"))), suiteId);
    }

    public Optional<String> findSuiteName(UUID suiteId) {
        var rows = jdbc.query("SELECT name FROM eval_suites WHERE id=?",
                Jdbc.reader(rs -> rs.getString(1)), suiteId);
        return rows.stream().findFirst();
    }
}
