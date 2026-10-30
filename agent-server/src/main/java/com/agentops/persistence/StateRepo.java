package com.agentops.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 状态仓储：检查点（工作记忆）与运行事件（文档 8.1 checkpoints / run_events）。
 * 关键事件随状态同事务提交，每个 run 使用递增 seq（文档 18.2）。
 */
@Repository
public class StateRepo {

    private final JdbcTemplate jdbc;

    public StateRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== 检查点 ====================

    public record CheckpointRow(UUID runId, long version, long workerEpoch,
                                String stateJson, Instant createdAt) {
    }

    /**
     * 保存检查点：version 递增，与状态推进同事务。
     * 持有租约的 worker 是该 run 的唯一写入者，版本可安全地 MAX+1。
     */
    public long insertCheckpoint(UUID tenantId, UUID userId, UUID sessionId, UUID runId,
                                 long workerEpoch, String stateJson) {
        jdbc.update("""
                INSERT INTO checkpoints (tenant_id, user_id, session_id, run_id, version, worker_epoch, state)
                VALUES (?,?,?,?,
                        (SELECT COALESCE(MAX(version),0)+1 FROM checkpoints
                         WHERE tenant_id=? AND run_id=?),
                        ?, ?::jsonb)
                """, tenantId, userId, sessionId, runId,
                tenantId, runId, workerEpoch, stateJson);
        return jdbc.queryForObject("""
                SELECT MAX(version) FROM checkpoints WHERE tenant_id=? AND run_id=?
                """, Long.class, tenantId, runId);
    }

    public Optional<CheckpointRow> findLatest(UUID tenantId, UUID runId) {
        var rows = jdbc.query("""
                SELECT run_id, version, worker_epoch, state::text, created_at
                FROM checkpoints WHERE tenant_id=? AND run_id=?
                ORDER BY version DESC LIMIT 1
                """, Jdbc.reader(rs -> new CheckpointRow(Jdbc.uuid(rs, "run_id"), rs.getLong("version"),
                        rs.getLong("worker_epoch"), rs.getString("state"), Jdbc.instant(rs, "created_at"))),
                tenantId, runId);
        return rows.stream().findFirst();
    }

    // ==================== 运行事件 ====================

    public record EventRow(UUID runId, long seq, String type, String payloadJson, Instant createdAt) {
    }

    public void insertEvent(UUID tenantId, UUID userId, UUID sessionId, UUID runId,
                            long seq, String type, String payloadJson) {
        jdbc.update("""
                INSERT INTO run_events (tenant_id, user_id, session_id, run_id, seq, type, safe_payload)
                VALUES (?,?,?,?,?,?,?::jsonb)
                ON CONFLICT (tenant_id, run_id, seq) DO NOTHING
                """,
                tenantId, userId, sessionId, runId, seq, type, payloadJson);
    }

    /** 断线重放：afterSeq 之后的事件（文档 21.2）。 */
    public List<EventRow> listAfter(UUID tenantId, UUID runId, long afterSeq, int limit) {
        return jdbc.query("""
                SELECT run_id, seq, type, safe_payload::text, created_at
                FROM run_events
                WHERE tenant_id=? AND run_id=? AND seq > ?
                ORDER BY seq ASC LIMIT ?
                """, Jdbc.reader(rs -> new EventRow(Jdbc.uuid(rs, "run_id"), rs.getLong("seq"),
                        rs.getString("type"), rs.getString("safe_payload"), Jdbc.instant(rs, "created_at"))),
                tenantId, runId, afterSeq, limit);
    }

    public Optional<EventRow> findFirst(UUID tenantId, UUID runId) {
        var rows = jdbc.query("""
                SELECT run_id, seq, type, safe_payload::text, created_at
                FROM run_events WHERE tenant_id=? AND run_id=? ORDER BY seq ASC LIMIT 1
                """, Jdbc.reader(rs -> new EventRow(Jdbc.uuid(rs, "run_id"), rs.getLong("seq"),
                        rs.getString("type"), rs.getString("safe_payload"), Jdbc.instant(rs, "created_at"))),
                tenantId, runId);
        return rows.stream().findFirst();
    }

    // ==================== 摘要 ====================

    public void saveSummary(UUID tenantId, UUID userId, UUID sessionId, long untilSeq,
                            long previousUntilSeq, String summary, int tokenCount, long contextVersion) {
        jdbc.update("""
                INSERT INTO summaries (tenant_id, user_id, session_id, until_seq,
                                       previous_until_seq, summary, token_count, source_context_version)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id, session_id, until_seq) DO UPDATE
                    SET summary=EXCLUDED.summary, token_count=EXCLUDED.token_count
                """, tenantId, userId, sessionId, untilSeq, previousUntilSeq, summary, tokenCount, contextVersion);
    }

    public record SummaryRow(long untilSeq, long previousUntilSeq, String summary, long contextVersion) {
    }

    /** 覆盖历史前缀 [1,S] 的最新摘要（文档 7.2）。 */
    public Optional<SummaryRow> findLatestSummary(UUID tenantId, UUID sessionId, long upToSeq) {
        var rows = jdbc.query("""
                SELECT until_seq, previous_until_seq, summary::text, source_context_version
                FROM summaries
                WHERE tenant_id=? AND session_id=? AND until_seq <= ?
                ORDER BY until_seq DESC LIMIT 1
                """, Jdbc.reader(rs -> new SummaryRow(rs.getLong("until_seq"), rs.getLong("previous_until_seq"),
                        rs.getString("summary"), rs.getLong("source_context_version"))),
                tenantId, sessionId, upToSeq);
        return rows.stream().findFirst();
    }
}
