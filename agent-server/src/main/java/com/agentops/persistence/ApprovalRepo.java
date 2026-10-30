package com.agentops.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 批准仓储（文档 8.1 run_approvals / 15.2 批准绑定）。 */
@Repository
public class ApprovalRepo {

    private final JdbcTemplate jdbc;

    public ApprovalRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ApprovalRow(UUID id, UUID runId, UUID stepId, String callId, String toolName,
                              String actionDigest, String status, String reason,
                              UUID approverId, Instant expiresAt, Instant consumedAt,
                              Instant decidedAt) {
    }

    public UUID insert(UUID tenantId, UUID userId, UUID sessionId, UUID runId, UUID stepId,
                       String callId, String toolName, String actionDigest, Instant expiresAt) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO run_approvals (tenant_id, user_id, session_id, run_id, id, step_id,
                                           call_id, tool_name, action_digest, status, expires_at)
                VALUES (?,?,?,?,?,?,?,?,?, 'pending', ?)
                """, tenantId, userId, sessionId, runId, id, stepId, callId, toolName,
                actionDigest, expiresAt);
        return id;
    }

    public Optional<ApprovalRow> findById(UUID tenantId, UUID userId, UUID approvalId) {
        var rows = jdbc.query("""
                SELECT id, run_id, step_id, call_id, tool_name, action_digest, status, reason,
                       approver_id, expires_at, consumed_at, decided_at
                FROM run_approvals WHERE tenant_id=? AND user_id=? AND id=?
                """, mapper(), tenantId, userId, approvalId);
        return rows.stream().findFirst();
    }

    /**
     * 查询该 run + digest 的有效批准（文档 15.2）。
     * 注意：只按 action_digest 匹配，不按 step_id——批准后恢复执行时 step_id 是新生成的随机值，
     * 而 digest（tenant+user+tool+version+canonical_args 的哈希）才唯一标识业务操作。
     */
    public Optional<ApprovalRow> findValid(UUID tenantId, UUID runId, UUID stepId, String digest, Instant now) {
        var rows = jdbc.query("""
                SELECT id, run_id, step_id, call_id, tool_name, action_digest, status, reason,
                       approver_id, expires_at, consumed_at, decided_at
                FROM run_approvals
                WHERE tenant_id=? AND run_id=? AND action_digest=?
                  AND status IN ('pending','approved') AND expires_at > ?
                ORDER BY created_at DESC LIMIT 1
                """, mapper(), tenantId, runId, digest, now);
        return rows.stream().findFirst();
    }

    /**
     * 批准决策（文档 15.2）：验证 digest 与当前状态后更新。
     * 参数变化必须重新评估——digest 不匹配则不会命中。
     */
    public Optional<ApprovalRow> decide(UUID tenantId, UUID userId, UUID approvalId,
                                        String decision, String reason, Instant now) {
        var rows = jdbc.query("""
                UPDATE run_approvals
                SET status=?, reason=?, approver_id=?, decided_at=?
                WHERE tenant_id=? AND user_id=? AND id=? AND status='pending' AND expires_at > ?
                RETURNING id, run_id, step_id, call_id, tool_name, action_digest, status, reason,
                          approver_id, expires_at, consumed_at, decided_at
                """, mapper(), "approved".equals(decision) ? "approved" : "rejected",
                reason, userId, now, tenantId, userId, approvalId, now);
        return rows.stream().findFirst();
    }

    /** 消费批准：批准消费和创建对应 attempt 在同一事务（文档 15.2）。 */
    public boolean consume(UUID tenantId, UUID approvalId, String expectedDigest) {
        return jdbc.update("""
                UPDATE run_approvals SET status='consumed', consumed_at=now()
                WHERE tenant_id=? AND id=? AND action_digest=? AND status='approved'
                """, tenantId, approvalId, expectedDigest) > 0;
    }

    public List<ApprovalRow> listPending(UUID tenantId, UUID userId, UUID runId) {
        return jdbc.query("""
                SELECT id, run_id, step_id, call_id, tool_name, action_digest, status, reason,
                       approver_id, expires_at, consumed_at, decided_at
                FROM run_approvals WHERE tenant_id=? AND user_id=? AND run_id=? AND status='pending'
                  AND expires_at > now() ORDER BY created_at, id
                """, mapper(), tenantId, userId, runId);
    }

    private org.springframework.jdbc.core.RowMapper<ApprovalRow> mapper() {
        return Jdbc.reader(rs -> new ApprovalRow(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs, "run_id"),
                Jdbc.uuid(rs, "step_id"), rs.getString("call_id"), rs.getString("tool_name"),
                rs.getString("action_digest"), rs.getString("status"), rs.getString("reason"),
                Jdbc.uuid(rs, "approver_id"), Jdbc.instant(rs, "expires_at"),
                Jdbc.instant(rs, "consumed_at"), Jdbc.instant(rs, "decided_at")));
    }
}
