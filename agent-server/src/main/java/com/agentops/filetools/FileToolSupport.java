package com.agentops.filetools;

import com.agentops.artifact.ArtifactService;
import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.ResultTypes.ArtifactRef;
import com.agentops.model.ModelPort;
import com.agentops.persistence.ArtifactRepo;
import com.agentops.persistence.RunRepo;
import com.agentops.project.ProjectService;
import com.agentops.tool.ToolRegistry.ToolContext;
import com.agentops.tool.WorkspaceService;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipFile;

/** Shared owner checks and bounded, immutable artifact IO for all file tools. */
@Service
public class FileToolSupport {
    public static final long MAX_BYTES = 20_000_000;
    public static final long MAX_OUTPUT_BYTES = 50_000_000;
    private static final Set<String> EXTENSIONS = Set.of("pptx", "docx", "xlsx", "pdf", "txt", "md", "csv", "json", "png", "jpg", "jpeg", "zip");
    private final ArtifactService artifacts;
    private final ArtifactRepo repo;
    private final RunRepo runs;
    private final ProjectService projects;
    private final WorkspaceService workspace;

    public FileToolSupport(ArtifactService artifacts, ArtifactRepo repo, RunRepo runs,
                           ProjectService projects, WorkspaceService workspace) {
        this.artifacts = artifacts;
        this.repo = repo;
        this.runs = runs;
        this.projects = projects;
        this.workspace = workspace;
    }

    public ArtifactService.Resolved input(Principal owner, String id, String... allowed) throws Exception {
        var item = artifacts.resolveForDownload(owner, UUID.fromString(id))
                .orElseThrow(() -> new IllegalArgumentException("文件不存在或无权限"));
        if (!Files.isRegularFile(item.file(), LinkOption.NOFOLLOW_LINKS) || Files.size(item.file()) > MAX_BYTES)
            throw new IllegalArgumentException("输入文件必须是20MB以内的普通文件");
        String ext = extension(item.originalName());
        if (allowed.length > 0 && !Set.of(allowed).contains(ext)) throw new IllegalArgumentException("此工具不支持该文件格式");
        return item;
    }

    public List<ArtifactRepo.ArtifactRow> list(Principal owner, UUID run) {
        var task = runs.findByIdOwner(owner, run).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        return repo.listReadyByOwnerSession(owner.tenantId(), owner.userId(), task.sessionId(), 60);
    }

    public Map<String, Object> info(Principal owner, String id) throws Exception {
        var item = input(owner, id);
        return Map.of("artifactId", id, "name", item.originalName(), "format", extension(item.originalName()),
                "bytes", Files.size(item.file()), "sha256", sha256(item.file()), "mimeType", item.mimeType());
    }

    public ArtifactRef importFile(Principal owner, ToolContext context, String scope, String relative) throws Exception {
        check(context);
        Path source = switch (scope) {
            case "project" -> projects.resolve(projects.required(owner, context.runId()), relative);
            case "workspace" -> workspace.resolve(owner, relative);
            default -> throw new IllegalArgumentException("scope必须为project或workspace");
        };
        String name = source.getFileName().toString();
        requireExtension(name);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) > MAX_BYTES)
            throw new IllegalArgumentException("仅导入20MB以内的普通文件");
        return output(owner, context, name, file -> {
            // Read through a bounded stream; do not move or overwrite the user's original file.
            try (var in = Files.newInputStream(source)) { copyBounded(in, file); }
        });
    }

    public ArtifactRef output(Principal owner, ToolContext context, String filename, Writer writer) throws Exception {
        check(context);
        String name = filename == null ? "" : filename.strip();
        if (name.isEmpty() || name.length() > 160 || name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*"))
            throw new IllegalArgumentException("文件名无效，不得包含目录或控制字符");
        requireExtension(name);
        var task = runs.findByIdOwner(owner, context.runId()).orElseThrow(() -> new IllegalArgumentException("任务不存在"));
        Path staging = artifacts.stagingPath(owner.tenantId(), UUID.randomUUID(), extension(name));
        try {
            writer.write(staging);
            check(context);
            if (!Files.isRegularFile(staging) || Files.size(staging) == 0 || Files.size(staging) > MAX_OUTPUT_BYTES)
                throw new IllegalArgumentException("生成文件为空或超过50MB上限");
            return artifacts.register(owner, task.sessionId(), context.runId(), name, mime(extension(name)), staging, "output");
        } finally { Files.deleteIfExists(staging); }
    }

    public ArtifactRef upload(Principal owner, ToolContext context, String name, InputStream input) throws Exception {
        return output(owner, context, name, file -> copyBounded(input, file));
    }

    private static void copyBounded(InputStream input, Path target) throws Exception {
        try (var out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192]; long count = 0; int n;
            while ((n = input.read(buffer)) != -1) {
                count += n;
                if (count > MAX_BYTES) throw new IllegalArgumentException("文件超过20MB上限");
                out.write(buffer, 0, n);
            }
        }
    }

    public static void check(ToolContext context) {
        if (Thread.currentThread().isInterrupted() || context.control() != null &&
                (context.control().cancellationRequested() || context.control().exceeded(Instant.now()))) throw new ModelPort.Cancelled();
    }

    public static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void requireExtension(String name) {
        if (!EXTENSIONS.contains(extension(name))) throw new IllegalArgumentException("支持pptx/docx/xlsx/pdf/txt/md/csv/json/png/jpg/zip");
    }

    public static String mime(String extension) {
        return switch (extension) {
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pdf" -> "application/pdf";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "zip" -> "application/zip";
            case "json" -> "application/json";
            default -> "text/plain;charset=UTF-8";
        };
    }

    public static String sha256(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Retain POI's zip-bomb defenses and reject active content or externally loaded resources. */
    public static OPCPackage office(Path file) throws Exception {
        try (var zip = new ZipFile(file.toFile())) {
            long size = 0; int entries = 0; var names = new java.util.HashSet<String>();
            for (var entry : java.util.Collections.list(zip.entries())) {
                String name = entry.getName();
                if (++entries > 3000 || !names.add(name) || entry.getSize() < 0 ||
                        (size += entry.getSize()) > 80_000_000 || name.toLowerCase(Locale.ROOT).contains("vbaproject") ||
                        name.contains("/activeX/") || name.contains("/oleObject"))
                    throw new IllegalArgumentException("Office包过大、重复条目或含不支持的活动内容");
            }
        }
        OPCPackage pkg = OPCPackage.open(file.toFile(), PackageAccess.READ);
        try {
            for (var rel : pkg.getRelationships()) if (rel.getTargetMode() == TargetMode.EXTERNAL)
                throw new IllegalArgumentException("不读取Office文件根目录中的外部资源");
            for (var part : pkg.getParts()) {
                if (part.isRelationshipPart()) continue;
                for (var rel : part.getRelationships()) {
                    if (rel.getTargetMode() == TargetMode.EXTERNAL && !rel.getRelationshipType().endsWith("/hyperlink"))
                        throw new IllegalArgumentException("不读取Office文件中的外部资源，请先嵌入图片和数据");
                }
            }
            return pkg;
        } catch (Exception error) { pkg.close(); throw error; }
    }

    public static BufferedImage image(Path file) throws Exception {
        try (var in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) throw new IllegalArgumentException("图片无法读取");
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IllegalArgumentException("仅支持可解码的PNG/JPEG图片");
            var reader = readers.next();
            try {
                reader.setInput(in);
                dimensions(reader.getWidth(0), reader.getHeight(0));
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }

    public static void dimensions(int width, int height) {
        if (width < 1 || height < 1 || width > 10000 || height > 10000 || (long) width * height > 16_000_000)
            throw new IllegalArgumentException("图片尺寸无效，最多1600万像素且单边不超过10000");
    }

    @FunctionalInterface public interface Writer { void write(Path file) throws Exception; }
}
