package com.agentops.artifact;

import com.agentops.config.AgentProperties;
import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.ResultTypes.ArtifactRef;
import com.agentops.persistence.ArtifactRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * 产物/附件服务（文档 32 节）：
 * 不把用户文件名当作实际存储路径；对象 ID 由服务端分配；
 * 下载入口每次核验当前身份与对象状态。
 *
 * 存储介质为本地文件系统（生产可替换为对象存储，接口不变）。
 */
@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    private final ArtifactRepo repo;
    private final Path baseDir;

    public ArtifactService(ArtifactRepo repo, AgentProperties props) {
        this.repo = repo;
        this.baseDir = Path.of(props.artifacts().dir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建产物目录: " + baseDir, e);
        }
    }

    /** 生成暂存路径（先写独立暂存对象，核验后再登记，文档 32.3）。 */
    public Path stagingPath(UUID tenantId, UUID objectId, String extension) {
        Path dir = baseDir.resolve(tenantId.toString());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir.resolve(objectId + (extension.startsWith(".") ? extension : "." + extension));
    }

    /** 登记已写入磁盘的产物（含 sha256 完整性校验）。 */
    public ArtifactRef register(Principal owner, UUID sessionId, UUID runId,
                                String originalName, String mimeType, Path file, String kind) {
        try {
            long size = Files.size(file);
            String sha256 = sha256(file);
            UUID objectId = UUID.randomUUID();
            Path target = baseDir.resolve(owner.tenantId().toString()).resolve(objectId + extensionOf(originalName));
            Files.createDirectories(target.getParent());
            Files.move(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            String objectKey = owner.tenantId() + "/" + target.getFileName();
            repo.insert(owner.tenantId(), owner.userId(), sessionId, runId, objectId, objectKey,
                    originalName, mimeType, size, sha256, kind);
            log.info("产物已登记 object={} size={} kind={}", objectId, size, kind);
            return new ArtifactRef(objectId.toString(), originalName, mimeType, size, sha256);
        } catch (IOException e) {
            throw new UncheckedIOException("产物登记失败: " + e.getMessage(), e);
        }
    }

    /** 下载核验（文档 32.3）：每次核验当前身份与对象状态。 */
    public Optional<Resolved> resolveForDownload(Principal owner, UUID objectId) {
        return repo.findReady(owner.tenantId(), owner.userId(), objectId).map(row -> {
            Path file = baseDir.resolve(owner.tenantId().toString())
                    .resolve(Path.of(row.objectKey()).getFileName().toString());
            if (!Files.exists(file)) {
                log.warn("产物对象不存在于存储层 object={}", objectId);
                return null;
            }
            return new Resolved(file, row.originalName(), row.mimeType(), row.byteSize());
        }).filter(java.util.Objects::nonNull);
    }

    public record Resolved(Path file, String originalName, String mimeType, long byteSize) {
    }

    private String extensionOf(String name) {
        if (name == null) return ".bin";
        int idx = name.lastIndexOf('.');
        return idx > 0 ? name.substring(idx) : ".bin";
    }

    private String sha256(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 保留期清理占位（文档 32.4：幂等删除作业）。 */
    public void deleteQuietly(UUID tenantId, UUID objectId) {
        repo.markDeleted(tenantId, objectId);
        Instant ignored = Instant.now();
    }
}
