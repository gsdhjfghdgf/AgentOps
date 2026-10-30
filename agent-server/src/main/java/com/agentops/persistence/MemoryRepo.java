package com.agentops.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 长期记忆仓储（文档 6 / 8.1 / 34 节）。
 *
 * 召回先按 tenant/user/scope/权限筛选再算距离（文档 6.4 / 8.3）；
 * 查询语义同时验证 state、valid_until、embedding_model 与 embedding_version=version，
 * 防止旧向量恢复已删除或修改的记忆。
 */
@Repository
public class MemoryRepo {

    private final JdbcTemplate jdbc;

    public MemoryRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record MemoryRow(UUID id, String scopeKey, String memoryKey, String kind, String content,
                            String authority, String state, long version, float importance,
                            String embeddingModel, Long embeddingVersion, Instant validUntil) {
    }

    /** owner 记忆版本（缓存 key 组成部分）。 */
    public long memoryVersion(UUID tenantId, UUID userId) {
        jdbc.update("""
                INSERT INTO owner_versions (tenant_id, user_id, memory_version)
                VALUES (?,?,1) ON CONFLICT (tenant_id, user_id) DO NOTHING
                """, tenantId, userId);
        var v = jdbc.queryForObject("""
                UPDATE owner_versions SET memory_version = memory_version + 1, updated_at=now()
                WHERE tenant_id=? AND user_id=? RETURNING memory_version
                """, Long.class, tenantId, userId);
        return v == null ? 0 : v;
    }

    /**
     * 写入/更新记忆（文档 6.3）：同 key 更新时 version+1 并使旧 embedding 失效。
     * authority 优先级：explicit 高于 inferred，旧来源不能覆盖新事实。
     */
    public UUID upsert(UUID tenantId, UUID userId, String scopeKey, String memoryKey,
                       String kind, String content, String authority, float importance,
                       Instant validUntil) {
        var existing = jdbc.query("""
                SELECT id, version, authority FROM memories
                WHERE tenant_id=? AND user_id=? AND scope_key=? AND memory_key=?
                """, Jdbc.reader(rs -> new Object[]{Jdbc.uuid(rs, "id"), rs.getLong("version"),
                rs.getString("authority")}), tenantId, userId, scopeKey, memoryKey);

        if (existing.isEmpty()) {
            var id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO memories (tenant_id, user_id, id, scope_key, memory_key, kind,
                                          content, authority, version, importance, valid_until)
                    VALUES (?,?,?,?,?,?,?,?,1,?,?)
                    """, tenantId, userId, id, scopeKey, memoryKey, kind, content,
                    authority, importance, validUntil);
            return id;
        }

        var row = existing.get(0);
        var id = (UUID) row[0];
        var version = (Long) row[1];
        var currentAuthority = (String) row[2];
        // inferred 不能覆盖 explicit（文档 6.3 冲突处理）
        if ("inferred".equals(authority) && "explicit".equals(currentAuthority)) {
            return id;
        }
        jdbc.update("""
                UPDATE memories
                SET content=?, authority=?, kind=?, importance=?, valid_until=?, state='active',
                    version=version+1, embedding=NULL, embedding_model=NULL, embedding_version=NULL,
                    updated_at=now()
                WHERE tenant_id=? AND id=?
                """, content, authority, kind, importance, validUntil, tenantId, id);
        return id;
    }

    /** 写回向量：仅当期望 version 仍一致时写入（文档 6.3 / 25 旧 embedding 迟到场景）。 */
    public boolean writeEmbedding(UUID tenantId, UUID memoryId, long expectedVersion,
                                  String embeddingLiteral, String model,String expectedContent) {
        return jdbc.update("""
                UPDATE memories
                SET embedding=?::vector, embedding_model=?, embedding_version=version
                WHERE tenant_id=? AND id=? AND version=? AND state='active' AND content=?
                """, embeddingLiteral, model, tenantId, memoryId, expectedVersion,expectedContent) > 0;
    }

    /** 撤回：保留逻辑撤回标记（文档 6.3）。 */
    public void revoke(UUID tenantId, UUID userId, String scopeKey, String memoryKey) {
        jdbc.update("""
                UPDATE memories SET state='revoked', version=version+1, embedding=NULL,embedding_model=NULL,embedding_version=NULL,updated_at=now()
                WHERE tenant_id=? AND user_id=? AND scope_key=? AND memory_key=? AND state='active'
                """, tenantId, userId, scopeKey, memoryKey);
    }

    public void revokeById(UUID tenantId,UUID userId, UUID memoryId) {
        jdbc.update("""
                UPDATE memories SET state='revoked', version=version+1, embedding=NULL,embedding_model=NULL,embedding_version=NULL,updated_at=now()
                WHERE tenant_id=? AND user_id=? AND id=? AND state='active'
                """, tenantId,userId, memoryId);
    }

    /** 相对稳定类记忆：按键直接查询（文档 6.2）。 */
    public Optional<MemoryRow> findActiveByKey(UUID tenantId, UUID userId, String scopeKey, String memoryKey) {
        var rows = jdbc.query("""
                SELECT id, scope_key, memory_key, kind, content, authority, state, version,
                       importance, embedding_model, embedding_version, valid_until
                FROM memories
                WHERE tenant_id=? AND user_id=? AND scope_key=? AND memory_key=?
                  AND state='active' AND (valid_until IS NULL OR valid_until > now())
                """, mapper(), tenantId, userId, scopeKey, memoryKey);
        return rows.stream().findFirst();
    }

    public List<MemoryRow> listActive(UUID tenantId,UUID userId,int limit) {
        return jdbc.query("""
                SELECT id,scope_key,memory_key,kind,content,authority,state,version,importance,
                       embedding_model,embedding_version,valid_until FROM memories
                WHERE tenant_id=? AND user_id=? AND state='active' AND (valid_until IS NULL OR valid_until>now())
                ORDER BY updated_at DESC LIMIT ?
                """,mapper(),tenantId,userId,limit);
    }

    public java.util.Map<UUID,String> latestEvidence(UUID tenantId,UUID userId) {
        var rows=jdbc.query("""
                SELECT DISTINCT ON(s.memory_id) s.memory_id,s.evidence FROM memory_sources s
                JOIN memories m ON m.tenant_id=s.tenant_id AND m.user_id=s.user_id AND m.id=s.memory_id
                WHERE s.tenant_id=? AND s.user_id=? AND m.state='active'
                ORDER BY s.memory_id,s.memory_version DESC,s.created_at DESC
                """,Jdbc.reader(rs->java.util.Map.entry(Jdbc.uuid(rs,"memory_id"),rs.getString("evidence"))),tenantId,userId);
        var result=new java.util.HashMap<UUID,String>();rows.forEach(entry->result.put(entry.getKey(),entry.getValue()));
        return result;
    }

    public Optional<UUID> upsertAutomatic(UUID tenantId,UUID userId,String key,String kind,String content,Instant observedAt) {
        return jdbc.query("""
                INSERT INTO memories(tenant_id,user_id,memory_key,scope_key,kind,content,authority,importance,observed_at)
                VALUES(?,?,?,'user',?,?,'inferred',0.6,?)
                ON CONFLICT(tenant_id,user_id,scope_key,memory_key) DO UPDATE SET
                    content=EXCLUDED.content,kind=EXCLUDED.kind,observed_at=EXCLUDED.observed_at,
                    version=memories.version+CASE WHEN memories.content IS DISTINCT FROM EXCLUDED.content THEN 1 ELSE 0 END,
                    embedding=CASE WHEN memories.content IS DISTINCT FROM EXCLUDED.content THEN NULL ELSE memories.embedding END,
                    embedding_model=CASE WHEN memories.content IS DISTINCT FROM EXCLUDED.content THEN NULL ELSE memories.embedding_model END,
                    embedding_version=CASE WHEN memories.content IS DISTINCT FROM EXCLUDED.content THEN NULL ELSE memories.embedding_version END,
                    updated_at=now()
                WHERE memories.state='active' AND memories.authority<>'explicit'
                  AND memories.observed_at<=EXCLUDED.observed_at
                RETURNING id
                """,Jdbc.reader(rs->Jdbc.uuid(rs,"id")),tenantId,userId,key,kind,content,observedAt).stream().findFirst();
    }

    public boolean autoEnabled(UUID tenantId,UUID userId) {
        var rows=jdbc.query("SELECT auto_enabled FROM memory_preferences WHERE tenant_id=? AND user_id=?",
                Jdbc.reader(rs->rs.getBoolean(1)),tenantId,userId);
        return rows.isEmpty() || rows.get(0);
    }
    public void setAutoEnabled(UUID tenantId,UUID userId,boolean enabled) {
        jdbc.update("""
                INSERT INTO memory_preferences(tenant_id,user_id,auto_enabled) VALUES(?,?,?)
                ON CONFLICT(tenant_id,user_id) DO UPDATE SET auto_enabled=EXCLUDED.auto_enabled
                """,tenantId,userId,enabled);
    }

    /**
     * 语义召回（文档 8.3）：先 owner/scope/状态过滤，再按余弦距离排序。
     * 验证 embedding_version=version，排除正文已修改但向量未更新的记录。
     */
    public List<MemoryRow> recall(UUID tenantId, UUID userId, String scopeKey,
                                  String queryVectorLiteral, String embeddingModel, int limit) {
        return jdbc.query("""
                SELECT id, scope_key, memory_key, kind, content, authority, state, version,
                       importance, embedding_model, embedding_version, valid_until
                FROM memories
                WHERE tenant_id=? AND user_id=? AND scope_key=?
                  AND state='active'
                  AND (valid_until IS NULL OR valid_until > now())
                  AND embedding IS NOT NULL
                  AND embedding_model=?
                  AND embedding_version=version
                ORDER BY embedding <=> ?::vector
                LIMIT ?
                """, mapper(), tenantId, userId, scopeKey, embeddingModel, queryVectorLiteral, limit);
    }

    /** 按 id 查询当前 version（向量写回前校验，文档 6.3）。 */
    public java.util.Optional<Long> findVersionById(UUID tenantId, UUID memoryId) {
        var rows = jdbc.query("""
                SELECT version FROM memories WHERE tenant_id=? AND id=?
                """, Jdbc.reader(rs -> rs.getLong(1)), tenantId, memoryId);
        return rows.stream().findFirst();
    }

    public void insertSource(UUID tenantId, UUID userId, UUID memoryId, long memoryVersion,
                             UUID sessionId, long sourceSeq, String evidence) {
        jdbc.update("""
                INSERT INTO memory_sources (tenant_id, user_id, memory_id, memory_version,
                                            session_id, source_seq, evidence)
                VALUES (?,?,?,?,?,?,?)
                ON CONFLICT DO NOTHING
                """, tenantId, userId, memoryId, memoryVersion, sessionId, sourceSeq, evidence);
    }

    private org.springframework.jdbc.core.RowMapper<MemoryRow> mapper() {
        return Jdbc.reader(rs -> new MemoryRow(Jdbc.uuid(rs, "id"), rs.getString("scope_key"),
                rs.getString("memory_key"), rs.getString("kind"), rs.getString("content"),
                rs.getString("authority"), rs.getString("state"), rs.getLong("version"),
                rs.getFloat("importance"), rs.getString("embedding_model"),
                rs.getObject("embedding_version") == null ? null : rs.getLong("embedding_version"),
                Jdbc.instant(rs, "valid_until")));
    }
}
