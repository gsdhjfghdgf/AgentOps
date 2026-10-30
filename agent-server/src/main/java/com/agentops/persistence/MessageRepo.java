package com.agentops.persistence;

import com.agentops.domain.AgentDomain.MessageRole;
import com.agentops.domain.Primitives.Principal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 消息仓储（文档 8.1 messages / 7.1 游标分页）。
 * seq 由 SessionRepo.allocateSeq 在同事务内分配。
 */
@Repository
public class MessageRepo {

    public record MessageRow(UUID tenantId, UUID userId, UUID sessionId, long seq, UUID runId,
                             MessageRole role, String payloadJson, long revision,
                             Integer tokenCount, String toolCallId, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    public MessageRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS =
            "tenant_id, user_id, session_id, seq, run_id, role, payload::text, revision, token_count, tool_call_id, created_at";

    private final org.springframework.jdbc.core.RowMapper<MessageRow> mapper = Jdbc.reader(rs ->
            new MessageRow(Jdbc.uuid(rs, "tenant_id"), Jdbc.uuid(rs, "user_id"), Jdbc.uuid(rs, "session_id"),
                    rs.getLong("seq"), Jdbc.uuid(rs, "run_id"), MessageRole.fromDb(rs.getString("role")),
                    rs.getString("payload"), rs.getLong("revision"),
                    rs.getObject("token_count") == null ? null : rs.getInt("token_count"),
                    rs.getString("tool_call_id"), Jdbc.instant(rs, "created_at")));

    public void insert(MessageRow row) {
        jdbc.update("""
                INSERT INTO messages (tenant_id, user_id, session_id, seq, run_id, role,
                                      payload, revision, token_count, tokenizer_id, tool_call_id)
                VALUES (?,?,?,?,?,?,?::jsonb,?,?,null,?)
                ON CONFLICT (tenant_id, session_id, seq) DO NOTHING
                """, row.tenantId(), row.userId(), row.sessionId(), row.seq(), row.runId(),
                row.role().db(), row.payloadJson(), row.revision(), row.tokenCount(), row.toolCallId());
    }

    /** 界面历史：seq < 游标 倒序取一页，显示时由调用方反转（文档 7.1，避免深分页 OFFSET）。 */
    public List<MessagePage> pageBefore(Principal owner, UUID sessionId, long beforeSeq, int limit) {
        var rows = jdbc.query("""
                SELECT %s FROM messages
                WHERE tenant_id=? AND user_id=? AND session_id=? AND seq < ?
                ORDER BY seq DESC LIMIT ?
                """.formatted(COLS), mapper, owner.tenantId(), owner.userId(), sessionId, beforeSeq, limit);
        return rows.stream().map(r -> new MessagePage(r, r.seq())).toList();
    }

    public java.util.Optional<String> lastAnswer(Principal owner, UUID sessionId, UUID runId) {
        return jdbc.query("""
                SELECT payload->>'content' FROM messages
                WHERE tenant_id=? AND user_id=? AND session_id=? AND run_id=?
                  AND role='assistant' AND payload->>'kind'='candidate'
                ORDER BY seq DESC LIMIT 1
                """, (rs, n) -> rs.getString(1), owner.tenantId(), owner.userId(), sessionId, runId)
                .stream().findFirst();
    }

    /** 拉取新消息：seq > last_seen ORDER BY seq ASC。 */
    public List<MessageRow> listAfter(Principal owner, UUID sessionId, long afterSeq, int limit) {
        return jdbc.query("""
                SELECT %s FROM messages
                WHERE tenant_id=? AND user_id=? AND session_id=? AND seq > ?
                ORDER BY seq ASC LIMIT ?
                """.formatted(COLS), mapper, owner.tenantId(), owner.userId(), sessionId, afterSeq, limit);
    }

    /** 模型上下文组装：加载 (untilSeq, R] 区间的原文（文档 7.2）。 */
    public List<MessageRow> contextTail(Principal owner, UUID sessionId, long fromSeq, long throughSeq, int limit) {
        var rows = jdbc.query("""
                SELECT %s FROM messages WHERE tenant_id=? AND user_id=? AND session_id=?
                  AND seq>? AND seq<=? ORDER BY seq DESC LIMIT ?
                """.formatted(COLS),mapper,owner.tenantId(),owner.userId(),sessionId,fromSeq,throughSeq,limit);
        return rows.stream().sorted(java.util.Comparator.comparingLong(MessageRow::seq)).toList();
    }

    public List<MessageRow> searchHistory(Principal owner, UUID sessionId, long beforeSeq, String query, int limit) {
        if (beforeSeq < 1 || query.length() > 300) throw new IllegalArgumentException("历史游标或关键词无效");
        var rows = jdbc.query("""
                SELECT %s FROM messages WHERE tenant_id=? AND user_id=? AND session_id=? AND seq<?
                  AND (?='' OR strpos(lower(payload->>'content'),lower(?))>0)
                ORDER BY seq DESC LIMIT ?
                """.formatted(COLS), mapper, owner.tenantId(), owner.userId(), sessionId, beforeSeq, query,query,limit);
        return rows.stream().sorted(java.util.Comparator.comparingLong(MessageRow::seq)).toList();
    }

    /** Full range remains for replay, validation and finalization; not for every model tick. */
    public List<MessageRow> listRange(UUID tenantId, UUID userId, UUID sessionId,
                                      long fromExclusive, long toInclusive) {
        return jdbc.query("""
                SELECT %s FROM messages
                WHERE tenant_id=? AND user_id=? AND session_id=? AND seq > ? AND seq <= ?
                ORDER BY seq ASC
                """.formatted(COLS), mapper, tenantId, userId, sessionId, fromExclusive, toInclusive);
    }

    public Optional<MessageRow> findByToolCallId(UUID tenantId, UUID sessionId, String toolCallId) {
        var rows = jdbc.query("""
                SELECT %s FROM messages
                WHERE tenant_id=? AND session_id=? AND tool_call_id=?
                ORDER BY seq DESC LIMIT 1
                """.formatted(COLS), mapper, tenantId, sessionId, toolCallId);
        return rows.stream().findFirst();
    }

    public List<MessageRow> listRunUsers(Principal owner,UUID runId) {
        return jdbc.query("""
                SELECT %s FROM messages WHERE tenant_id=? AND user_id=? AND run_id=? AND role='user'
                ORDER BY seq LIMIT 100
                """.formatted(COLS),mapper,owner.tenantId(),owner.userId(),runId);
    }

    /** 页记录：附带本页最小 seq（下一页游标）。 */
    public record MessagePage(MessageRow row, long seq) {
    }
}
