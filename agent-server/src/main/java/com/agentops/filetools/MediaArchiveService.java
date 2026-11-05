package com.agentops.filetools;

import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.ResultTypes.ArtifactRef;
import com.agentops.infra.Json;
import com.agentops.tool.ToolRegistry.ToolContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@Service
public class MediaArchiveService {
    private final FileToolSupport files;
    private final Json json;
    public MediaArchiveService(FileToolSupport files, Json json) { this.files = files; this.json = json; }

    public ArtifactRef pdfExtract(Principal owner, ToolContext context, String id, String title, int first, int last) throws Exception {
        var input = files.input(owner, id, "pdf");
        return files.output(owner, context, title + ".pdf", path -> {
            try (var source = Loader.loadPDF(input.file().toFile()); var target = new PDDocument()) {
                pdfBounds(source);
                if (first < 1 || last < first || last > source.getNumberOfPages() || last - first >= 100)
                    throw new IllegalArgumentException("提取范围无效，一次最多100页，页码从1开始");
                for (int page = first; page <= last; page++) {
                    FileToolSupport.check(context); staticPage(target, source, page - 1);
                }
                target.save(path.toFile());
            }
        });
    }

    public ArtifactRef pdfMerge(Principal owner, ToolContext context, String title, String idsJson) throws Exception {
        var ids = ids(idsJson, 10);
        // Resolve all owners before starting any output operation.
        var inputs = new ArrayList<com.agentops.artifact.ArtifactService.Resolved>();
        for (String id : ids) inputs.add(files.input(owner, id, "pdf"));
        return files.output(owner, context, title + ".pdf", path -> {
            var opened = new ArrayList<PDDocument>();
            try (var target = new PDDocument()) {
                for (var input : inputs) {
                    var source = Loader.loadPDF(input.file().toFile()); opened.add(source);
                    pdfBounds(source);
                    if (source.getNumberOfPages() + target.getNumberOfPages() > 200) throw new IllegalArgumentException("合并后最多200页");
                    for (int page = 0; page < source.getNumberOfPages(); page++) {
                        FileToolSupport.check(context); staticPage(target, source, page);
                    }
                }
                target.save(path.toFile());
            } finally {
                // PDFBox 3 reads objects lazily; imported page resources remain backed by their input.
                for (var source : opened) source.close();
            }
        });
    }

    private static void staticPage(PDDocument target, PDDocument source, int page) throws Exception {
        var original = source.getPage(page); var copy = target.importPage(original);
        copy.setResources(original.getResources()); copy.setAnnotations(List.of()); copy.setActions(null);
    }

    public ArtifactRef pdfRender(Principal owner, ToolContext context, String id, int page) throws Exception {
        var input = files.input(owner, id, "pdf");
        return files.output(owner, context, "PDF第" + page + "页.png", path -> {
            try (var source = Loader.loadPDF(input.file().toFile())) {
                pdfBounds(source);
                if (page < 1 || page > source.getNumberOfPages()) throw new IllegalArgumentException("PDF页码不存在");
                var box = source.getPage(page - 1).getCropBox(); float scale = 1200f / Math.max(box.getWidth(), box.getHeight());
                if (!Float.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("PDF页面尺寸无效");
                FileToolSupport.dimensions((int) Math.ceil(box.getWidth() * scale), (int) Math.ceil(box.getHeight() * scale));
                FileToolSupport.check(context);
                var image = new PDFRenderer(source).renderImage(page - 1, scale, ImageType.RGB);
                ImageIO.write(image, "png", path.toFile());
            }
        });
    }

    private static void pdfBounds(PDDocument doc) {
        if (doc.isEncrypted() || !doc.getCurrentAccessPermission().canAssembleDocument() || doc.getNumberOfPages() < 1 || doc.getNumberOfPages() > 200)
            throw new IllegalArgumentException("只处理未加密、允许操作、1～200页的PDF");
    }

    public Map<String, Object> imageInfo(Principal owner, String id) throws Exception {
        var input = files.input(owner, id, "png", "jpg", "jpeg"); var image = FileToolSupport.image(input.file());
        return Map.of("artifactId", id, "width", image.getWidth(), "height", image.getHeight(), "alpha", image.getColorModel().hasAlpha(),
                "bytes", Files.size(input.file()), "notice", "来自真实图片解码，不推断图片语义或拍摄时间。");
    }

    public ArtifactRef imageResize(Principal owner, ToolContext context, String id, String title, int width, int height) throws Exception {
        FileToolSupport.dimensions(width, height);
        var source = files.input(owner, id, "png", "jpg", "jpeg"); var image = FileToolSupport.image(source.file());
        double scale = Math.min(1, Math.min((double) width / image.getWidth(), (double) height / image.getHeight()));
        int w = Math.max(1, (int) Math.round(image.getWidth() * scale)), h = Math.max(1, (int) Math.round(image.getHeight() * scale));
        return files.output(owner, context, title + ".png", path -> {
            var target = new BufferedImage(w, h, image.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            var graphics = target.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(image, 0, 0, w, h, null);
            } finally { graphics.dispose(); }
            ImageIO.write(target, "png", path.toFile());
        });
    }

    public ArtifactRef archive(Principal owner, ToolContext context, String title, String idsJson) throws Exception {
        var ids = ids(idsJson, 50); var inputs = new ArrayList<com.agentops.artifact.ArtifactService.Resolved>(); long size = 0;
        for (var id : ids) {
            var input = files.input(owner, id); size += Files.size(input.file());
            if (size > FileToolSupport.MAX_OUTPUT_BYTES) throw new IllegalArgumentException("归档输入总计最多50MB");
            inputs.add(input);
        }
        return files.output(owner, context, title + ".zip", path -> {
            var manifest = new ArrayList<Map<String, Object>>();
            try (var zip = new ZipOutputStream(Files.newOutputStream(path), StandardCharsets.UTF_8)) {
                int index = 0;
                for (var input : inputs) {
                    FileToolSupport.check(context);
                    String name = (++index) + "-" + input.originalName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
                    zip.putNextEntry(new ZipEntry(name)); Files.copy(input.file(), zip); zip.closeEntry();
                    manifest.add(Map.of("name", name, "sourceArtifactId", ids.get(index - 1), "bytes", Files.size(input.file()), "sha256", FileToolSupport.sha256(input.file())));
                }
                zip.putNextEntry(new ZipEntry("manifest.json")); zip.write(json.write(manifest).getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        });
    }

    public Map<String, Object> archiveList(Principal owner, String id) throws Exception {
        var input = files.input(owner, id, "zip"); var entries = new ArrayList<Map<String, Object>>(); long total = 0;
        try (var zip = new ZipFile(input.file().toFile(), StandardCharsets.UTF_8)) {
            var seen = new HashSet<String>();
            for (var entry : Collections.list(zip.entries())) {
                if (entries.size() >= 500 || entry.getName().length() > 500 || entry.getSize() < 0 || (total += entry.getSize()) > 100_000_000)
                    throw new IllegalArgumentException("ZIP超过500项/100MB解压大小或元数据不合法，不展开读取");
                String name = entry.getName(); boolean unsafe = name.contains("\\") || name.startsWith("/") || name.contains(":") ||
                        Arrays.asList(name.split("/")).contains("..") || !seen.add(name);
                entries.add(Map.of("name", name, "bytes", entry.getSize(), "directory", entry.isDirectory(), "unsafeName", unsafe));
            }
        }
        return Map.of("entries", entries, "uncompressedBytes", total, "notice", "只读取目录元数据，不解压、不执行内容；unsafeName代表不安全或重复名称。");
    }

    public ArtifactRef chart(Principal owner, ToolContext context, String title, String dataJson) throws Exception {
        var data = PresentationService.object(dataJson, Set.of("type", "labels", "values", "unit"));
        var labels = data.path("labels"); var values = data.path("values"); String type = data.path("type").asText("bar");
        if (!Set.of("bar", "line").contains(type) || !labels.isArray() || !values.isArray() || labels.isEmpty() || labels.size() > 12 || values.size() != labels.size())
            throw new IllegalArgumentException("chart须为bar/line，labels和values须等长且为1～12项");
        var names = new ArrayList<String>(); double[] numbers = new double[values.size()]; double minimum = 0, maximum = 0;
        for (int i = 0; i < values.size(); i++) {
            names.add(PresentationService.scalar(labels.get(i), 12));
            if (!values.get(i).isNumber() || !Double.isFinite(values.get(i).asDouble()) || Math.abs(values.get(i).asDouble()) > 1e12)
                throw new IllegalArgumentException("图表数据须为绝对值不超过1万亿的有限数字");
            numbers[i] = values.get(i).asDouble(); minimum = Math.min(minimum, numbers[i]); maximum = Math.max(maximum, numbers[i]);
        }
        double min = minimum, max = maximum == minimum ? maximum + 1 : maximum;
        String unit = PresentationService.text(data, "unit", 20);
        return files.output(owner, context, title + ".png", path -> {
            var image = new BufferedImage(1400, 850, BufferedImage.TYPE_INT_RGB); var g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Color.WHITE); g.fillRect(0, 0, 1400, 850); g.setColor(Color.decode("#244D38"));
                g.setFont(new Font("Microsoft YaHei", Font.BOLD, 32)); g.drawString(PresentationService.clip(title, 40), 70, 65);
                g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 18)); g.drawString(unit, 70, 100);
                int left = 125, top = 140, plotWidth = 1200, plotHeight = 550;
                for (int tick = 0; tick <= 5; tick++) {
                    int y = top + tick * plotHeight / 5; double value = max - tick * (max - min) / 5;
                    g.setColor(Color.LIGHT_GRAY); g.drawLine(left, y, left + plotWidth, y); g.setColor(Color.DARK_GRAY);
                    g.drawString(String.format(Locale.ROOT, "%.4g", value), 10, y + 5);
                }
                int zero = top + (int) (max / (max - min) * plotHeight), previousX = 0, previousY = 0;
                for (int i = 0; i < numbers.length; i++) {
                    int x = left + (int) ((i + 0.5) * plotWidth / numbers.length), y = top + (int) ((max - numbers[i]) / (max - min) * plotHeight);
                    g.setColor(Color.decode("#38694D"));
                    if (type.equals("bar")) g.fillRect(x - 25, Math.min(y, zero), 50, Math.max(1, Math.abs(zero - y)));
                    else { if (i > 0) g.drawLine(previousX, previousY, x, y); g.fillOval(x - 5, y - 5, 10, 10); }
                    g.setColor(Color.DARK_GRAY); g.drawString(names.get(i), x - 28, top + plotHeight + 36);
                    g.drawString(values.get(i).asText(), x - 25, Math.max(120, y - 12)); previousX = x; previousY = y;
                }
                g.setColor(Color.GRAY); g.drawString("数据由调用者提供；图像不证明数据真实性。", 70, 815);
            } finally { g.dispose(); }
            ImageIO.write(image, "png", path.toFile());
        });
    }

    private List<String> ids(String source, int maximum) {
        var array = json.tree(source);
        if (!array.isArray() || array.isEmpty() || array.size() > maximum) throw new IllegalArgumentException("artifactIdsJson须为1～" + maximum + "个对象ID的数组");
        var ids = new ArrayList<String>();
        for (var id : array) {
            if (!id.isTextual()) throw new IllegalArgumentException("文件对象ID须为字符串");
            ids.add(UUID.fromString(id.asText()).toString());
        }
        return ids;
    }
}
