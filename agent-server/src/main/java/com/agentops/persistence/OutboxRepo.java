package com.agentops.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbox 仓储（文档 18.3）：首版直接使用 PG outbox 作为可靠后台任务队列。
 * event_key 唯一约束防重复登记；至少一次执行要求消费端幂等。
 */
@Repository
public class OutboxRepo {

    private final JdbcTemplate jdbc;

    public OutboxRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record OutboxRow(UUID id, UUID tenantId, UUID userId, String eventKey, String type, String payloadJson,
                            int attemptCount) {
    }

    public void insert(UUID tenantId, UUID userId, String eventKey, String type, String payloadJson) {
        jdbc.update("""
                INSERT INTO outbox (tenant_id, user_id, event_key, type, payload)
                VALUES (?,?,?,?,?::jsonb)
                ON CONFLICT (tenant_id, event_key) DO NOTHING
                """, tenantId, userId, eventKey, type, payloadJson);
    }

    /** 领取一批待执行事件：短事务设置 processing + claim_token + 租约（文档 18.3）。 */
    public List<OutboxRow> claim(int limit, UUID claimToken, Instant leaseUntil) {
        return jdbc.query("""
                UPDATE outbox SET status='processing', claim_token=?,
                                  lease_until=now() + make_interval(secs => ?::int)
                WHERE id IN (
                    SELECT id FROM outbox
                    WHERE status='pending' AND available_at <= now()
                    ORDER BY available_at, id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                )
                RETURNING id, tenant_id, user_id, event_key, type, payload::text, attempt_count
                """,
                Jdbc.reader(rs -> new OutboxRow(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs,"tenant_id"),Jdbc.uuid(rs,"user_id"),rs.getString("event_key"),
                        rs.getString("type"), rs.getString("payload"), rs.getInt("attempt_count"))),
                claimToken, "30", limit);
    }

    /** 完成时匹配 token（防止旧 worker 误标完成）。 */
    public boolean markDone(UUID id, UUID claimToken) {
        return jdbc.update("""
                UPDATE outbox SET status='done', finished_at=now(), lease_until=NULL
                WHERE id=? AND claim_token=? AND status='processing'
                """, id, claimToken) > 0;
    }

    /** 失败退避：指数退避由调用方给出 nextAvailableAt。 */
    public boolean markRetry(UUID id, UUID claimToken, String error, Instant nextAvailableAt) {
        return jdbc.update("""
                UPDATE outbox
                SET status='pending', claim_token=NULL, lease_until=NULL,
                    attempt_count=attempt_count+1, last_error=?, available_at=?
                WHERE id=? AND claim_token=? AND status='processing'
                """, error, nextAvailableAt, id, claimToken) > 0;
    }

    /** 超重试进入 dead（文档 18.3）。 */
    public boolean markDead(UUID id, UUID claimToken, String error) {
        return jdbc.update("""
                UPDATE outbox
                SET status='dead', claim_token=NULL, lease_until=NULL,
                    attempt_count=attempt_count+1, last_error=?, finished_at=now()
                WHERE id=? AND claim_token=? AND status='processing'
                """, error, id, claimToken) > 0;
    }

    /** 租约过期可被其他 worker 领取。 */
    public int requeueExpired() {
        return jdbc.update("""
                UPDATE outbox SET status='pending', claim_token=NULL, lease_until=NULL,
                                  attempt_count=attempt_count+1
                WHERE status='processing' AND lease_until IS NOT NULL AND lease_until < now()
                """);
    }

    public long countByStatus(String status) {
        var n = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE status=?",
                Long.class, status);
        return n == null ? 0 : n;
    }

    public Optional<OutboxRow> findById(UUID id) {
        var rows = jdbc.query("""
                SELECT id, tenant_id, user_id, event_key, type, payload::text, attempt_count
                FROM outbox WHERE id=?
                """, Jdbc.reader(rs -> new OutboxRow(Jdbc.uuid(rs, "id"), Jdbc.uuid(rs,"tenant_id"),Jdbc.uuid(rs,"user_id"),rs.getString("event_key"),
                rs.getString("type"), rs.getString("payload"), rs.getInt("attempt_count"))), id);
        return rows.stream().findFirst();
    }
}
