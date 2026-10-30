package com.agentops.knowledge;

import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.ResultTypes.EvidenceRef;
import com.agentops.model.EmbeddingRouter;
import com.agentops.persistence.KnowledgeRepo;
import com.agentops.persistence.KnowledgeRepo.ChunkHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库服务（文档 10 / 30 节）：
 * 上传 → 解析 → 分块与元数据 → 向量化 → 检索（关键词+向量融合）→ 权限过滤。
 *
 * 文档更新时新建版本，完成解析与索引后才切换 active_version（文档 30.4）；
 * 候选块继承文档权限，召回后再次验证租户、版本、删除状态与有效期（文档 30.2）。
 */
@Service
public class KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeService.class);

    private static final String PARSER_VERSION = "text-parser-1.0";
    private static final String CHUNKER_VERSION = "heading-chunker-1.0";
    private static final int MAX_CHUNK_CHARS = 600;

    private final KnowledgeRepo repo;
    private final EmbeddingRouter embedding;

    public KnowledgeService(KnowledgeRepo repo, EmbeddingRouter embedding) {
        this.repo = repo;
        this.embedding = embedding;
    }

    /** 导入文档（同步版，适合中小文档；超大文档应走 Outbox 后台任务，文档 30.1）。 */
    public Map<String, Object> importDocument(Principal owner, String title, String content, String ownerScope) {
        if(title==null || title.isBlank() || title.length()>240 || content==null || content.isBlank()
                || content.length()>DocumentTextParser.MAX_CHARS)
            throw new com.agentops.api.RunApplicationService.BadRequestException("标题须为 1～240 字，正文不能为空且不能超过 20 万字");
        UUID documentId;
        var existing = repo.listDocuments(owner.tenantId(), owner.userId()).stream()
                .filter(d -> d.title().equals(title)).findFirst();
        if (existing.isPresent()) {
            documentId = existing.get().documentId();
        } else {
            documentId = repo.insertDocument(owner.tenantId(), owner.userId(), title,
                    ownerScope == null ? "user" : ownerScope);
        }

        int version = repo.insertVersion(owner.tenantId(), owner.userId(), documentId,
                sha256(content), PARSER_VERSION, CHUNKER_VERSION, embedding.modelId(),
                content.getBytes(StandardCharsets.UTF_8).length);

        try {
            var chunks = chunk(content);
            for (int i = 0; i < chunks.size(); i++) {
                var c = chunks.get(i);
                String vectorLiteral = embedding.toLiteral(embedding.embed(c.text()));
                repo.insertChunk(owner.tenantId(), owner.userId(), documentId, version,
                        i, c.text(), c.heading(), c.text().length(), vectorLiteral, embedding.modelId());
            }
            repo.finishVersion(owner.tenantId(), documentId, version, chunks.size());
            repo.activateVersion(owner.tenantId(), documentId, version);
            log.info("知识文档已导入 doc={} version={} chunks={}", documentId, version, chunks.size());
            return Map.of("documentId", documentId.toString(), "version", version,
                    "chunkCount", chunks.size(), "state", "ready");
        } catch (RuntimeException e) {
            repo.failVersion(owner.tenantId(), documentId, version, e.getMessage());
            throw e;
        }
    }

    public record RetrievedChunk(String documentId, String title, int version, String heading,
                                 String content, double score) {
    }

    /**
     * 检索（文档 30.2）：先限定可访问范围，再做关键词与向量召回、融合、去重。
     * 关键词利于精确编号与术语，向量用于语义匹配。
     */
    public List<RetrievedChunk> search(Principal owner, String ownerScope, String query, int limit) {
        String scope = ownerScope == null ? "user" : ownerScope;
        var hits = new LinkedHashMap<String, RetrievedChunk>();

        // 向量召回
        String vectorLiteral = embedding.toLiteral(embedding.embed(query));
        for (var hit : repo.search(owner.tenantId(), owner.userId(), scope, vectorLiteral,
                embedding.modelId(), Math.max(limit * 2, 8))) {
            hits.putIfAbsent(hit.documentId() + ":" + hit.chunkId(),
                    toRetrieved(hit, hit.score()));
        }

        // 关键词召回（提取中英数词做精确匹配）
        for (var keyword : extractKeywords(query)) {
            for (var hit : repo.keywordSearch(owner.tenantId(), owner.userId(), scope, keyword, 6)) {
                var key = hit.documentId() + ":" + hit.chunkId();
                // 融合：关键词命中加权（RRF 简化版）
                var existing = hits.get(key);
                if (existing == null) {
                    hits.put(key, toRetrieved(hit, 0.5));
                } else if (existing.score() < 0.85) {
                    hits.put(key, new RetrievedChunk(existing.documentId(), existing.title(),
                            existing.version(), existing.heading(), existing.content(),
                            Math.min(0.99, existing.score() + 0.15)));
                }
            }
        }

        return hits.values().stream()
                .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed())
                .limit(limit)
                .toList();
    }

    /** 转换为证据引用（文档 30.3：引用在访问时重新授权，不泄露内部存储地址）。 */
    public List<EvidenceRef> toEvidence(List<RetrievedChunk> chunks) {
        var refs = new ArrayList<EvidenceRef>();
        for (var c : chunks) {
            String snippet = c.content().length() > 160 ? c.content().substring(0, 160) + "…" : c.content();
            refs.add(new EvidenceRef("knowledge", c.documentId() + "#" + c.version(),
                    String.valueOf(c.version()), "[" + c.title() + "] " + snippet));
        }
        return refs;
    }

    public List<Map<String, Object>> listDocuments(Principal owner) {
        var counts=repo.activeChunkCounts(owner.tenantId(),owner.userId());
        return repo.listDocuments(owner.tenantId(), owner.userId()).stream()
                .<Map<String, Object>>map(d -> Map.of(
                        "documentId", d.documentId().toString(),
                        "title", d.title(),
                        "activeVersion", d.activeVersion(),"chunkCount",counts.getOrDefault(d.documentId(),0)))
                .toList();
    }

    public void deleteDocument(Principal owner, UUID documentId) {
        if(repo.findDocument(owner.tenantId(),owner.userId(),documentId).isEmpty())
            throw new com.agentops.api.RunApplicationService.NotFoundException("文档不存在");
        repo.softDeleteDocument(owner.tenantId(), documentId);
    }

    public Map<String,Object> read(Principal owner,UUID documentId) {
        var document=repo.findDocument(owner.tenantId(),owner.userId(),documentId)
                .orElseThrow(()->new com.agentops.api.RunApplicationService.NotFoundException("文档不存在"));
        var chunks=repo.activeChunks(owner.tenantId(),owner.userId(),documentId);
        String content=chunks.stream().limit(30).map(KnowledgeRepo.IndexChunk::content).collect(java.util.stream.Collectors.joining("\n"));
        boolean truncated=content.length()>12000 || chunks.size()>30;
        return Map.of("documentId",documentId.toString(),"title",document.title(),"version",document.activeVersion(),
                "content",content.substring(0,Math.min(12000,content.length())),"truncated",truncated);
    }

    /** 保留原分块，构建完整新索引后再切换，旧文档在构建期间仍可检索。 */
    public Map<String,Object> reindex(Principal owner, UUID documentId) {
        if (repo.findDocument(owner.tenantId(),owner.userId(),documentId).isEmpty())
            throw new com.agentops.api.RunApplicationService.NotFoundException("文档不存在");
        var chunks=repo.activeChunks(owner.tenantId(),owner.userId(),documentId);
        if(chunks.isEmpty()) throw new com.agentops.api.RunApplicationService.BadRequestException("文档没有可重建的内容，请重新导入");
        String content=chunks.stream().map(KnowledgeRepo.IndexChunk::content).collect(java.util.stream.Collectors.joining("\n"));
        int version=repo.insertVersion(owner.tenantId(),owner.userId(),documentId,sha256(content),
                PARSER_VERSION,CHUNKER_VERSION,embedding.modelId(),content.getBytes(StandardCharsets.UTF_8).length);
        try {
            for(var chunk:chunks) repo.insertChunk(owner.tenantId(),owner.userId(),documentId,version,
                    chunk.ordinal(),chunk.content(),chunk.heading(),chunk.content().length(),
                    embedding.toLiteral(embedding.embed(chunk.content())),embedding.modelId());
            repo.finishVersion(owner.tenantId(),documentId,version,chunks.size());
            repo.activateVersion(owner.tenantId(),documentId,version);
            return Map.of("documentId",documentId.toString(),"version",version,"chunkCount",chunks.size());
        } catch(RuntimeException e) {
            repo.failVersion(owner.tenantId(),documentId,version,e.getMessage());
            throw e;
        }
    }

    // ==================== 分块与工具方法 ====================

    record Chunk(String heading, String text) {
    }

    /** 按标题/段落分块，限制长度并保留父级上下文（文档 30.1）。 */
    List<Chunk> chunk(String content) {
        var chunks = new ArrayList<Chunk>();
        String[] lines = content.split("\r?\n");
        String currentHeading = "";
        var buffer = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("#")) {
                flush(chunks, currentHeading, buffer);
                currentHeading = line.replaceFirst("^#+\\s*", "").trim();
                if (!currentHeading.isEmpty()) buffer.append("【").append(currentHeading).append("】\n");
            } else {
                buffer.append(line).append('\n');
            }
            if (buffer.length() >= MAX_CHUNK_CHARS && line.isBlank()) {
                flush(chunks, currentHeading, buffer);
            }
        }
        flush(chunks, currentHeading, buffer);
        // 去除空白块
        chunks.removeIf(c -> c.text().isBlank());
        return chunks;
    }

    private void flush(List<Chunk> chunks, String heading, StringBuilder buffer) {
        String text=buffer.toString().strip();
        int start=0;
        while(start<text.length()) {
            int end=Math.min(start+MAX_CHUNK_CHARS,text.length());
            if(end<text.length() && Character.isHighSurrogate(text.charAt(end-1)))end--;
            chunks.add(new Chunk(heading,text.substring(start,end)));
            if(end==text.length())break;
            start=end-80;
            if(Character.isLowSurrogate(text.charAt(start)))start++;
        }
        buffer.setLength(0);
    }

    /** 提取检索关键词：中文双字滑窗 + 英文单词/数字编号（简化但可演示）。 */
    private List<String> extractKeywords(String query) {
        var keywords = new LinkedHashSet<String>();
        var m = java.util.regex.Pattern.compile("[a-zA-Z][a-zA-Z0-9_-]{2,}|\\d{3,}").matcher(query);
        while (m.find()) keywords.add(m.group());
        String compact = query.replaceAll("\\s+", "");
        for (int i = 0; i + 2 <= compact.length() && keywords.size() < 6; i++) {
            String gram = compact.substring(i, i + 2);
            if (gram.matches("[\\u4e00-\\u9fa5]{2}")) keywords.add(gram);
        }
        return List.copyOf(keywords);
    }

    private RetrievedChunk toRetrieved(ChunkHit hit, double score) {
        return new RetrievedChunk(hit.documentId().toString(), hit.title(), hit.version(),
                hit.heading(), hit.content(), score);
    }

    private String sha256(String content) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
