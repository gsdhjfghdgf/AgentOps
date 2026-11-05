package com.agentops.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 产物仓储（文档 8.1 artifacts / 32 节对象登记与状态）。 */
@Repository
public class ArtifactRepo {

    private final JdbcTemplate jdbc;

    public ArtifactRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record ArtifactRow(UUID objectId, String objectKey, String originalName, String mimeType,
                              long byteSize, String sha256, String kind, String state,
                              UUID runId, Instant createdAt) {
    }

    public void insert(UUID tenantId, UUID userId, UUID sessionId, UUID runId, UUID objectId,
                       String objectKey, String originalName, String mimeType,
                       long byteSize, String sha256, String kind) {
        jdbc.update("""
                INSERT INTO artifacts (tenant_id, user_id, session_id, run_id, object_id,
                                       object_key, original_name, mime_type, byte_size, sha256, kind, state)
                VALUES (?,?,?,?,?,?,?,?,?,?,?, 'ready')
                """, tenantId, userId, sessionId, runId, objectId, objectKey, originalName,
                mimeType, byteSize, sha256, kind);
    }

    /** 下载入口每次核验当前身份和对象状态（文档 32.3）。 */
    public Optional<ArtifactRow> findReady(UUID tenantId, UUID userId, UUID objectId) {
        var rows = jdbc.query("""
                SELECT object_id, object_key, original_name, mime_type, byte_size, sha256,
                       kind, state, run_id, created_at
                FROM artifacts
                WHERE tenant_id=? AND user_id=? AND object_id=? AND state='ready' AND deleted_at IS NULL
                """, mapper(), tenantId, userId, objectId);
        return rows.stream().findFirst();
    }

    public List<ArtifactRow> listByRun(UUID tenantId, UUID runId) {
        return jdbc.query("""
                SELECT object_id, object_key, original_name, mime_type, byte_size, sha256,
                       kind, state, run_id, created_at
                FROM artifacts WHERE tenant_id=? AND run_id=? AND deleted_at IS NULL
                """, mapper(), tenantId, runId);
    }

    public List<ArtifactRow> listReadyByOwnerRun(UUID tenantId, UUID userId, UUID runId) {
        return jdbc.query("""
                SELECT object_id, object_key, original_name, mime_type, byte_size, sha256,
                       kind, state, run_id, created_at
                FROM artifacts WHERE tenant_id=? AND user_id=? AND run_id=?
                  AND state='ready' AND deleted_at IS NULL ORDER BY created_at, object_id
                """, mapper(), tenantId, userId, runId);
    }

    public List<ArtifactRow> listReadyByOwnerSession(UUID tenantId, UUID userId, UUID sessionId, int limit) {
        return jdbc.query("""
                SELECT object_id, object_key, original_name, mime_type, byte_size, sha256,
                       kind, state, run_id, created_at
                FROM artifacts WHERE tenant_id=? AND user_id=? AND session_id=?
                  AND state='ready' AND deleted_at IS NULL ORDER BY created_at DESC, object_id LIMIT ?
                """, mapper(), tenantId, userId, sessionId, Math.min(Math.max(limit, 1), 100));
    }

    public void markDeleted(UUID tenantId, UUID objectId) {
        jdbc.update("""
                UPDATE artifacts SET state='deleted', deleted_at=now()
                WHERE tenant_id=? AND object_id=?
                """, tenantId, objectId);
    }

    private org.springframework.jdbc.core.RowMapper<ArtifactRow> mapper() {
        return Jdbc.reader(rs -> new ArtifactRow(Jdbc.uuid(rs, "object_id"), rs.getString("object_key"),
                rs.getString("original_name"), rs.getString("mime_type"), rs.getLong("byte_size"),
                rs.getString("sha256"), rs.getString("kind"), rs.getString("state"),
                Jdbc.uuid(rs, "run_id"), Jdbc.instant(rs, "created_at")));
    }
}
