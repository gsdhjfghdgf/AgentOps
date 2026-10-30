package com.agentops.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 知识库仓储（文档 8.1 / 30 节）：文档、版本、分块与向量检索。 */
@Repository
public class KnowledgeRepo {

    private final JdbcTemplate jdbc;

    public KnowledgeRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== 文档 ====================

    public record DocRow(UUID documentId, String title, String ownerScope, int activeVersion, String state) {
    }

    public UUID insertDocument(UUID tenantId, UUID userId, String title, String ownerScope) {
        var id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO knowledge_documents (tenant_id, user_id, document_id, title, owner_scope)
                VALUES (?,?,?,?,?)
                """, tenantId, userId, id, title, ownerScope);
        return id;
    }

    public Optional<DocRow> findDocument(UUID tenantId, UUID userId, UUID documentId) {
        var rows = jdbc.query("""
                SELECT document_id, title, owner_scope, active_version, state
                FROM knowledge_documents
                WHERE tenant_id=? AND user_id=? AND document_id=? AND state='active'
                """, Jdbc.reader(rs -> new DocRow(Jdbc.uuid(rs, "document_id"), rs.getString("title"),
                rs.getString("owner_scope"), rs.getInt("active_version"), rs.getString("state"))),
                tenantId, userId, documentId);
        return rows.stream().findFirst();
    }

    public List<DocRow> listDocuments(UUID tenantId, UUID userId) {
        return jdbc.query("""
                SELECT document_id, title, owner_scope, active_version, state
                FROM knowledge_documents
                WHERE tenant_id=? AND user_id=? AND state='active'
                ORDER BY created_at DESC
                """, Jdbc.reader(rs -> new DocRow(Jdbc.uuid(rs, "document_id"), rs.getString("title"),
                rs.getString("owner_scope"), rs.getInt("active_version"), rs.getString("state"))),
                tenantId, userId);
    }

    public java.util.Map<UUID,Integer> activeChunkCounts(UUID tenantId,UUID userId) {
        var rows=jdbc.query("""
                SELECT d.document_id,v.chunk_count FROM knowledge_documents d
                JOIN document_versions v ON v.tenant_id=d.tenant_id AND v.document_id=d.document_id AND v.version=d.active_version
                WHERE d.tenant_id=? AND d.user_id=? AND d.state='active'
                """,Jdbc.reader(rs->java.util.Map.entry(Jdbc.uuid(rs,"document_id"),rs.getInt("chunk_count"))),tenantId,userId);
        var result=new java.util.HashMap<UUID,Integer>();rows.forEach(row->result.put(row.getKey(),row.getValue()));return result;
    }

    /** 新版本完成解析与索引后再切换 active_version（文档 30.4）。 */
    public void activateVersion(UUID tenantId, UUID documentId, int version) {
        jdbc.update("""
                UPDATE knowledge_documents SET active_version=?, updated_at=now()
                WHERE tenant_id=? AND document_id=?
                """, version, tenantId, documentId);
    }

    public void softDeleteDocument(UUID tenantId, UUID documentId) {
        jdbc.update("""
                UPDATE knowledge_documents SET state='deleted', updated_at=now()
                WHERE tenant_id=? AND document_id=?
                """, tenantId, documentId);
    }

    // ==================== 版本 ====================

    public int insertVersion(UUID tenantId, UUID userId, UUID documentId, String contentHash,
                             String parserVersion, String chunkerVersion, String embeddingModel,
                             long byteSize) {
        var next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version),0)+1 FROM document_versions
                WHERE tenant_id=? AND document_id=?
                """, Integer.class, tenantId, documentId);
        int version = next == null ? 1 : next;
        jdbc.update("""
                INSERT INTO document_versions (tenant_id, user_id, document_id, version, content_hash,
                                               parser_version, chunker_version, embedding_model,
                                               state, byte_size)
                VALUES (?,?,?,?,?,?,?,?, 'building', ?)
                """, tenantId, userId, documentId, version, contentHash, parserVersion,
                chunkerVersion, embeddingModel, byteSize);
        return version;
    }

    public void finishVersion(UUID tenantId, UUID documentId, int version, int chunkCount) {
        jdbc.update("""
                UPDATE document_versions
                SET state='ready', chunk_count=?, activated_at=now()
                WHERE tenant_id=? AND document_id=? AND version=?
                """, chunkCount, tenantId, documentId, version);
    }

    public void failVersion(UUID tenantId, UUID documentId, int version, String error) {
        jdbc.update("""
                UPDATE document_versions SET state='failed', error=?
                WHERE tenant_id=? AND document_id=? AND version=?
                """, error, tenantId, documentId, version);
    }

    // ==================== 分块 ====================

    public void insertChunk(UUID tenantId, UUID userId, UUID documentId, int version,
                            int ordinal, String content, String heading, int tokenCount,
                            String embeddingLiteral, String embeddingModel) {
        jdbc.update("""
                INSERT INTO document_chunks (tenant_id, user_id, document_id, version, ordinal,
                                             content, heading, token_count, embedding, embedding_model, embedding_version)
                VALUES (?,?,?,?,?,?,?,? ,?::vector,?,1)
                """, tenantId, userId, documentId, version, ordinal, content, heading,
                tokenCount, embeddingLiteral, embeddingModel);
    }

    public record ChunkHit(UUID documentId, int version, UUID chunkId, int ordinal,
                           String content, String heading, double score, String title, int activeVersion) {
    }

    public record IndexChunk(String content, String heading, int ordinal) {}

    public List<IndexChunk> activeChunks(UUID tenantId, UUID userId, UUID documentId) {
        return jdbc.query("""
                SELECT c.content,c.heading,c.ordinal FROM document_chunks c
                JOIN knowledge_documents d ON d.tenant_id=c.tenant_id AND d.document_id=c.document_id
                WHERE c.tenant_id=? AND c.user_id=? AND c.document_id=?
                  AND d.state='active' AND c.version=d.active_version ORDER BY c.ordinal
                """, Jdbc.reader(rs -> new IndexChunk(rs.getString("content"),rs.getString("heading"),rs.getInt("ordinal"))),
                tenantId,userId,documentId);
    }

    /**
     * 向量检索（文档 30.2）：候选块继承文档权限；召回后验证租户、版本、
     * 删除状态与 active_version，旧版本块不能泄露。
     */
    public List<ChunkHit> search(UUID tenantId, UUID userId, String ownerScope,
                                 String queryVectorLiteral, String embeddingModel, int limit) {
        return jdbc.query("""
                SELECT c.document_id, c.version, c.chunk_id, c.ordinal, c.content, c.heading,
                       1 - (c.embedding <=> ?::vector) AS score,
                       d.title, d.active_version
                FROM document_chunks c
                JOIN knowledge_documents d
                  ON d.tenant_id=c.tenant_id AND d.document_id=c.document_id
                WHERE c.tenant_id=? AND c.user_id=?
                  AND d.owner_scope=? AND d.state='active'
                  AND c.version=d.active_version
                  AND c.embedding IS NOT NULL
                  AND c.embedding_model=?
                ORDER BY c.embedding <=> ?::vector
                LIMIT ?
                """, Jdbc.reader(rs -> new ChunkHit(Jdbc.uuid(rs, "document_id"), rs.getInt("version"),
                Jdbc.uuid(rs, "chunk_id"), rs.getInt("ordinal"), rs.getString("content"),
                rs.getString("heading"), rs.getDouble("score"), rs.getString("title"),
                rs.getInt("active_version"))),
                queryVectorLiteral, tenantId, userId, ownerScope, embeddingModel, queryVectorLiteral, limit);
    }

    /** 关键词检索（编号/术语精确匹配，文档 30.2 与向量融合）。 */
    public List<ChunkHit> keywordSearch(UUID tenantId, UUID userId, String ownerScope,
                                        String keyword, int limit) {
        return jdbc.query("""
                SELECT c.document_id, c.version, c.chunk_id, c.ordinal, c.content, c.heading,
                       0.0 AS score, d.title, d.active_version
                FROM document_chunks c
                JOIN knowledge_documents d
                  ON d.tenant_id=c.tenant_id AND d.document_id=c.document_id
                WHERE c.tenant_id=? AND c.user_id=?
                  AND d.owner_scope=? AND d.state='active'
                  AND c.version=d.active_version
                  AND (c.content ILIKE ? OR c.heading ILIKE ? OR d.title ILIKE ?)
                LIMIT ?
                """, Jdbc.reader(rs -> new ChunkHit(Jdbc.uuid(rs, "document_id"), rs.getInt("version"),
                Jdbc.uuid(rs, "chunk_id"), rs.getInt("ordinal"), rs.getString("content"),
                rs.getString("heading"), rs.getDouble("score"), rs.getString("title"),
                rs.getInt("active_version"))),
                tenantId, userId, ownerScope, "%" + keyword + "%", "%" + keyword + "%",
                "%" + keyword + "%", limit);
    }
}
