package com.agentops.api.web;

import com.agentops.artifact.ArtifactService;
import com.agentops.domain.Primitives.Principal;
import com.agentops.persistence.ArtifactRepo;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 产物接口（文档 32.3）：
 * 下载入口每次核验当前身份和对象状态；文件名使用服务端分配的对象 ID，
 * 用户提供的原始文件名只作为下载时的展示名（Content-Disposition）。
 */
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {

    private final ArtifactService artifacts;
    private final ArtifactRepo repo;
    private final com.agentops.persistence.RunRepo runs;

    public ArtifactController(ArtifactService artifacts, ArtifactRepo repo,
                              com.agentops.persistence.RunRepo runs) {
        this.artifacts = artifacts;
        this.repo = repo;
        this.runs = runs;
    }

    @GetMapping("/by-run/{runId}")
    public Map<String, Object> listByRun(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID runId) {
        var owner = ApiController.principal(tenantId, userId);
        if (runs.findByIdOwner(owner, runId).isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "任务不存在");
        }
        return Map.of("artifacts", repo.listReadyByOwnerRun(owner.tenantId(), owner.userId(), runId)
                .stream().map(row -> Map.of("objectId", row.objectId().toString(),
                        "name", row.originalName() == null ? "产物" : row.originalName(),
                        "kind", row.kind(), "byteSize", row.byteSize())).toList());
    }

    @GetMapping("/{objectId}/download")
    public ResponseEntity<Resource> download(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID objectId) {
        var owner = Principal.of(UUID.fromString(tenantId), UUID.fromString(userId));
        var resolved = artifacts.resolveForDownload(owner, objectId).orElse(null);
        if (resolved == null) {
            return ResponseEntity.notFound().build();
        }

        var resource = new FileSystemResource(resolved.file());
        // 原始文件名只作为展示名，且做 RFC 5987 编码防止响应头注入
        String safeName = sanitize(resolved.originalName());
        return ResponseEntity.ok()
                .contentType(parseMediaType(resolved.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"download" + extensionOf(safeName)
                                + "\"; filename*=UTF-8''" + encode(safeName))
                .contentLength(resolved.byteSize())
                .body(resource);
    }

    @GetMapping("/{objectId}")
    public Map<String, Object> meta(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID objectId) {
        var owner = Principal.of(UUID.fromString(tenantId), UUID.fromString(userId));
        var row = repo.findReady(owner.tenantId(), owner.userId(), objectId)
                .orElseThrow(() -> new ApiController.ApiException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "产物不存在或无权限"));
        return Map.of(
                "objectId", row.objectId().toString(),
                "originalName", row.originalName() == null ? "" : row.originalName(),
                "mimeType", row.mimeType() == null ? "application/octet-stream" : row.mimeType(),
                "byteSize", row.byteSize(),
                "sha256", row.sha256() == null ? "" : row.sha256(),
                "createdAt", row.createdAt().toString());
    }

    private MediaType parseMediaType(String mime) {
        try {
            return mime == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(mime);
        } catch (RuntimeException e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private String sanitize(String name) {
        if (name == null) return "file";
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        return cleaned.isEmpty() ? "file" : cleaned;
    }

    private String extensionOf(String name) {
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(idx) : "";
    }

    private String encode(String name) {
        return java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
