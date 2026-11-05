package com.agentops.api.web;

import com.agentops.domain.Primitives.Principal;
import com.agentops.knowledge.KnowledgeService;
import com.agentops.memory.MemoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 知识库与记忆接口（文档 10 / 30 / 6 节）。
 */
@RestController
@RequestMapping("/api")
public class KnowledgeController {

    private final KnowledgeService knowledge;
    private final MemoryService memory;

    public KnowledgeController(KnowledgeService knowledge, MemoryService memory) {
        this.knowledge = knowledge;
        this.memory = memory;
    }

    // ==================== 知识库 ====================

    public record ImportDocumentRequest(String title, String content, String ownerScope) {
    }

    @PostMapping("/knowledge/documents")
    public ResponseEntity<Map<String, Object>> importDocument(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @RequestBody ImportDocumentRequest req) {
        var owner = ApiController.principal(tenantId, userId);
        if (req.title() == null || req.title().isBlank()
                || req.content() == null || req.content().isBlank()) {
            throw new ApiController.ApiException(HttpStatus.BAD_REQUEST, "title 和 content 不能为空");
        }
        var result = knowledge.importDocument(owner, req.title(), req.content(), req.ownerScope());
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping("/knowledge/documents")
    public Map<String, Object> listDocuments(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId) {
        var owner = ApiController.principal(tenantId, userId);
        return Map.of("documents", knowledge.listDocuments(owner));
    }

    @DeleteMapping("/knowledge/documents/{documentId}")
    public Map<String, Object> deleteDocument(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID documentId) {
        var owner = ApiController.principal(tenantId, userId);
        knowledge.deleteDocument(owner, documentId);
        return Map.of("ok", true);
    }

    @PostMapping("/knowledge/documents/{documentId}/reindex")
    public Map<String,Object> reindex(@RequestHeader("X-Tenant-Id") String tenantId,
                                     @RequestHeader("X-User-Id") String userId,@PathVariable UUID documentId) {
        return knowledge.reindex(ApiController.principal(tenantId,userId),documentId);
    }

    public record SearchRequest(String query, String ownerScope, Integer limit) {
    }

    @PostMapping("/knowledge/search")
    public Map<String, Object> search(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @RequestBody SearchRequest req) {
        var owner = ApiController.principal(tenantId, userId);
        int limit = req.limit() == null ? 5 : Math.min(Math.max(req.limit(), 1), 20);
        var chunks = knowledge.search(owner, req.ownerScope(), req.query(), limit).stream()
                .<Map<String, Object>>map(c -> Map.of(
                        "documentId", c.documentId(), "title", c.title(),
                        "version", c.version(), "heading", c.heading() == null ? "" : c.heading(),
                        "content", c.content(), "score", c.score()))
                .toList();
        return Map.of("chunks", chunks);
    }

    // ==================== 记忆 ====================

    public record SaveMemoryRequest(String scopeKey, String memoryKey, String kind,
                                    String content, Long validForSeconds) {
    }

    /** 显式保存记忆（文档 6.4：立即跨会话生效）。 */
    @PostMapping("/memories")
    public Map<String, Object> saveMemory(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @RequestBody SaveMemoryRequest req) {
        var owner = ApiController.principal(tenantId, userId);
        if(req.memoryKey()==null || req.memoryKey().isBlank() || req.memoryKey().length()>160
                || req.content()==null || req.content().isBlank() || req.content().length()>2000
                || (req.kind()!=null && !java.util.Set.of("preference","fact","experience","episode").contains(req.kind())))
            throw new ApiController.ApiException(HttpStatus.BAD_REQUEST,"请提供有效的记忆类型和 1～2000 字的内容");
        if(req.validForSeconds()!=null&&(req.validForSeconds()<1||req.validForSeconds()>315360000L))throw new ApiController.ApiException(HttpStatus.BAD_REQUEST,"有效期须为1秒到10年之间");
        if(req.scopeKey()!=null&&(req.scopeKey().isBlank()||req.scopeKey().length()>160))throw new ApiController.ApiException(HttpStatus.BAD_REQUEST,"记忆范围须为1～160字");
        Duration validFor = req.validForSeconds() == null ? null : Duration.ofSeconds(req.validForSeconds());
        return memory.saveExplicit(owner,
                req.scopeKey() == null ? "user" : req.scopeKey(),
                req.memoryKey(), req.kind() == null ? "fact" : req.kind(),
                req.content(), validFor);
    }

    @GetMapping("/memories")
    public Map<String, Object> recallMemories(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(required = false) String query,
            @RequestParam(defaultValue = "6") int limit) {
        var owner = ApiController.principal(tenantId, userId);
        var recalled = query==null || query.isBlank() ? memory.list(owner,Math.min(Math.max(limit,1),100))
                : memory.recall(owner,"user",query,Math.min(Math.max(limit,1),20));
        return Map.of("memories", recalled);
    }

    @DeleteMapping("/memories/{memoryKey}")
    public Map<String, Object> revokeMemory(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String memoryKey) {
        var owner = ApiController.principal(tenantId, userId);
        memory.revoke(owner, "user", memoryKey);
        return Map.of("ok", true);
    }

    @GetMapping("/memory-settings")
    public Map<String,Object> memorySettings(@RequestHeader("X-Tenant-Id")String tenant,@RequestHeader("X-User-Id")String user) {
        return memory.settings(ApiController.principal(tenant,user));
    }
    public record MemorySettingsRequest(boolean enabled) {}
    @PutMapping("/memory-settings")
    public Map<String,Object> updateMemorySettings(@RequestHeader("X-Tenant-Id")String tenant,@RequestHeader("X-User-Id")String user,
            @RequestBody MemorySettingsRequest request) {
        var owner=ApiController.principal(tenant,user); memory.setAutoEnabled(owner,request.enabled());
        return memory.settings(owner);
    }
}
