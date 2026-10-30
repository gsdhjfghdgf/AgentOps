package com.agentops.persistence;

import com.agentops.domain.Primitives.Principal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话仓储（文档 8.1 sessions / 7.1 消息序号分配）。
 * 消息序号使用会话行原子更新分配，并与 INSERT 同事务，不用 MAX(seq)+1。
 */
@Repository
public class SessionRepo {

    public record SessionRow(UUID tenantId, UUID userId, UUID id, String title,
                             long lastSeq, long contextVersion,
                             Instant createdAt, Instant updatedAt, boolean pinned, Instant archivedAt, String kind, UUID projectId) {
        public SessionRow(UUID tenantId, UUID userId, UUID id, String title, long lastSeq, long contextVersion,
                          Instant createdAt, Instant updatedAt, boolean pinned, Instant archivedAt, String kind) {
            this(tenantId,userId,id,title,lastSeq,contextVersion,createdAt,updatedAt,pinned,archivedAt,kind,null);
        }
    }

    private final JdbcTemplate jdbc;

    public SessionRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS =
            "tenant_id, user_id, id, title, last_seq, context_version, created_at, updated_at, pinned, archived_at, kind, project_id";

    private final org.springframework.jdbc.core.RowMapper<SessionRow> mapper = Jdbc.reader(rs ->
            new SessionRow(Jdbc.uuid(rs, "tenant_id"), Jdbc.uuid(rs, "user_id"), Jdbc.uuid(rs, "id"),
                    rs.getString("title"), rs.getLong("last_seq"), rs.getLong("context_version"),
                    Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "updated_at"), rs.getBoolean("pinned"),
                    Jdbc.instant(rs, "archived_at"), rs.getString("kind"), Jdbc.uuid(rs, "project_id")));

    /** T1 创建：返回新会话。同一 owner+id 重复创建幂等返回已有会话。 */
    public SessionRow create(Principal owner, UUID id, String title) {
        return create(owner, id, title, "chat");
    }

    public SessionRow create(Principal owner, UUID id, String title, String kind) {
        jdbc.update("""
                INSERT INTO sessions (tenant_id, user_id, id, title, kind)
                VALUES (?,?,?,?,?)
                ON CONFLICT (tenant_id, id) DO NOTHING
                """, owner.tenantId(), owner.userId(), id, title == null ? "" : title, kind);
        return findById(owner, id).orElseThrow();
    }

    /** Project membership is chosen once, and checked against the current owner in the same statement. */
    public SessionRow createProjectSession(Principal owner, UUID id, String title, UUID projectId) {
        int inserted=jdbc.update("""
                INSERT INTO sessions (tenant_id,user_id,id,title,kind,project_id)
                SELECT ?,?,?,?,'chat',id FROM agent_projects WHERE tenant_id=? AND user_id=? AND id=?
                """,owner.tenantId(),owner.userId(),id,title==null?"":title,owner.tenantId(),owner.userId(),projectId);
        if(inserted!=1) throw new com.agentops.api.RunApplicationService.NotFoundException("项目不存在");
        return findById(owner,id).orElseThrow();
    }

    public Optional<SessionRow> findById(Principal owner, UUID sessionId) {
        var rows = jdbc.query("""
                SELECT %s FROM sessions
                WHERE tenant_id=? AND user_id=? AND id=? AND deleted_at IS NULL
                """.formatted(COLS), mapper, owner.tenantId(), owner.userId(), sessionId);
        return rows.stream().findFirst();
    }

    /** Serialize task creation for a session before checking idempotency and single-flight. */
    public boolean lockOwned(Principal owner, UUID sessionId) {
        return !jdbc.query("""
                SELECT id FROM sessions
                WHERE tenant_id=? AND user_id=? AND id=? AND deleted_at IS NULL
                FOR UPDATE
                """, Jdbc.reader(rs -> Jdbc.uuid(rs, "id")),
                owner.tenantId(), owner.userId(), sessionId).isEmpty();
    }

    /** 会话列表：owner + updated_at 倒序（活跃会话索引）。 */
    public List<SessionRow> listByOwner(Principal owner, int limit) {
        return search(owner, limit, "", false);
    }

    public List<SessionRow> search(Principal owner, int limit, String query, boolean archived) {
        return jdbc.query("""
                SELECT %s FROM sessions
                WHERE tenant_id=? AND user_id=? AND deleted_at IS NULL
                  AND kind='chat' AND (archived_at IS NOT NULL)=?
                  AND (?='' OR strpos(lower(title),lower(?))>0)
                ORDER BY pinned DESC, updated_at DESC, id DESC LIMIT ?
                """.formatted(COLS), mapper, owner.tenantId(), owner.userId(), archived, query, query, limit);
    }

    public void organize(Principal owner, UUID sessionId, Boolean pinned, Boolean archived) {
        jdbc.update("""
                UPDATE sessions SET pinned=COALESCE(?,pinned),
                    archived_at=CASE WHEN CAST(? AS boolean) IS NULL THEN archived_at WHEN CAST(? AS boolean) THEN now() ELSE NULL END,
                    updated_at=now() WHERE tenant_id=? AND user_id=? AND id=? AND deleted_at IS NULL
                """, pinned, archived, archived, owner.tenantId(), owner.userId(), sessionId);
    }

    public void autoTitle(Principal owner, UUID sessionId, String message) {
        String title = message.replaceAll("\\s+", " ").strip();
        title = title.substring(0, title.offsetByCodePoints(0, Math.min(40, title.codePointCount(0,title.length()))));
        jdbc.update("""
                UPDATE sessions SET title=? WHERE tenant_id=? AND user_id=? AND id=?
                  AND last_seq=0 AND (title='' OR title='新会话')
                """, title, owner.tenantId(), owner.userId(), sessionId);
    }

    public int clearHistory(Principal owner) {
        return jdbc.update("""
                UPDATE sessions SET deleted_at=now(),updated_at=now()
                WHERE tenant_id=? AND user_id=? AND deleted_at IS NULL
                """, owner.tenantId(), owner.userId());
    }

    public void lockHistory(Principal owner) {
        jdbc.query("""
                SELECT id FROM sessions WHERE tenant_id=? AND user_id=? AND deleted_at IS NULL
                ORDER BY id FOR UPDATE
                """, Jdbc.reader(rs -> Jdbc.uuid(rs,"id")),owner.tenantId(),owner.userId());
    }

    public void rename(Principal owner, UUID sessionId, String title) {
        jdbc.update("""
                UPDATE sessions SET title=?, updated_at=now()
                WHERE tenant_id=? AND user_id=? AND id=? AND deleted_at IS NULL
                """, title, owner.tenantId(), owner.userId(), sessionId);
    }

    public void softDelete(Principal owner, UUID sessionId) {
        jdbc.update("""
                UPDATE sessions SET deleted_at=now(), updated_at=now()
                WHERE tenant_id=? AND user_id=? AND id=? AND deleted_at IS NULL
                """, owner.tenantId(), owner.userId(), sessionId);
    }

    /**
     * 分配消息序号（文档 7.1）：
     * UPDATE ... SET last_seq = last_seq + 1 RETURNING last_seq，与消息 INSERT 同事务。
     */
    public long allocateSeq(Principal owner, UUID sessionId) {
        var seq = jdbc.queryForObject("""
                UPDATE sessions
                SET last_seq = last_seq + 1, context_version = context_version + 1, updated_at = now()
                WHERE tenant_id=? AND user_id=? AND id=? AND deleted_at IS NULL
                RETURNING last_seq
                """, Long.class, owner.tenantId(), owner.userId(), sessionId);
        if (seq == null) {
            throw new IllegalStateException("会话不存在或已删除: " + sessionId);
        }
        return seq;
    }

    /** 当前一致快照：last_seq + context_version（文档 7.2 短读事务）。 */
    public Optional<VersionedCursor> currentCursor(Principal owner, UUID sessionId) {
        var rows = jdbc.query("""
                SELECT last_seq, context_version FROM sessions
                WHERE tenant_id=? AND user_id=? AND id=? AND deleted_at IS NULL
                """, Jdbc.reader(rs -> new VersionedCursor(rs.getLong(1), rs.getLong(2))),
                owner.tenantId(), owner.userId(), sessionId);
        return rows.stream().findFirst();
    }

    public record VersionedCursor(long lastSeq, long contextVersion) {
    }
}
