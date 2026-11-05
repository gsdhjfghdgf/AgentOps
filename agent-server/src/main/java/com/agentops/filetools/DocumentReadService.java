package com.agentops.filetools;

import com.agentops.domain.Primitives.Principal;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Source-addressed document excerpts. No OCR or factual verification is implied by text extraction. */
@Service
public class DocumentReadService {
    private final FileToolSupport files;
    public DocumentReadService(FileToolSupport files) { this.files = files; }
    public record Unit(int number, String kind, String text) {}

    public Map<String, Object> read(Principal owner, String id, int unit, int offset) throws Exception {
        if (unit < 1 || offset < 0) throw new IllegalArgumentException("unit从1开始，offset不得为负数");
        var units = units(owner, id); if (unit > units.size()) throw new IllegalArgumentException("文档片段不存在");
        var current = units.get(unit - 1); int end = (int) Math.min((long) current.text().length(), (long) offset + 6000);
        if (offset > current.text().length()) throw new IllegalArgumentException("offset超出片段长度");
        return Map.of("artifactId", id, "unit", unit, "unitKind", current.kind(), "totalUnits", units.size(), "text", current.text().substring(offset, end),
                "nextOffset", end, "unitComplete", end == current.text().length(), "notice", "文字提取不验证事实；扫描文档无文本时需OCR，不能当作没有内容。");
    }

    public Map<String, Object> search(Principal owner, String id, String query) throws Exception {
        if (query.isBlank() || query.length() > 200) throw new IllegalArgumentException("搜索词须为1～200字的普通文本");
        var results = new ArrayList<Map<String, Object>>(); boolean limited = false;
        for (var unit : units(owner, id)) {
            int from = 0, index;
            while ((index = unit.text().indexOf(query, from)) >= 0) {
                if (results.size() >= 30) { limited = true; break; }
                results.add(Map.of("unit", unit.number(), "kind", unit.kind(), "offset", index,
                        "excerpt", unit.text().substring(Math.max(0, index - 100), Math.min(unit.text().length(), index + query.length() + 180))));
                from = index + Math.max(1, query.length());
            }
            if (limited) break;
        }
        return Map.of("artifactId", id, "query", query, "matches", results, "limited", limited,
                "notice", "普通文本精确匹配；不做OCR或语义检索。用document_read按unit/offset继续核对。");
    }

    List<Unit> units(Principal owner, String id) throws Exception {
        var input = files.input(owner, id, "docx", "pptx", "pdf", "txt", "md", "csv", "json");
        String ext = FileToolSupport.extension(input.originalName()); var result = new ArrayList<Unit>();
        switch (ext) {
            case "pdf" -> {
                try (var doc = Loader.loadPDF(input.file().toFile())) {
                    if (doc.getNumberOfPages() > 200 || !doc.getCurrentAccessPermission().canExtractContent()) throw new IllegalArgumentException("PDF最多200页且必须允许文本提取");
                    var stripper = new PDFTextStripper();
                    for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                        stripper.setStartPage(page); stripper.setEndPage(page); add(result, "page", stripper.getText(doc));
                    }
                }
            }
            case "pptx" -> {
                try (var deck = new XMLSlideShow(FileToolSupport.office(input.file()))) {
                    if (deck.getSlides().size() > 100) throw new IllegalArgumentException("PPT最多100页");
                    for (var slide : deck.getSlides()) {
                        var text = new StringBuilder();
                        for (var shape : slide.getShapes()) {
                            if (shape instanceof XSLFTextShape box) text.append(box.getText()).append('\n');
                            if (shape instanceof XSLFTable table) for (var row : table.getRows()) {
                                for (var cell : row.getCells()) text.append(cell.getText()).append('\t'); text.append('\n');
                            }
                        }
                        add(result, "slide", text.toString());
                    }
                }
            }
            case "docx" -> {
                try (var doc = new XWPFDocument(FileToolSupport.office(input.file()))) {
                    if (doc.getBodyElements().size() > 3000) throw new IllegalArgumentException("Word最多3000段或表格");
                    for (var element : doc.getBodyElements()) {
                        if (element instanceof XWPFParagraph paragraph) add(result, "paragraph", paragraph.getText());
                        if (element instanceof XWPFTable table) add(result, "table", table.getText());
                    }
                }
            }
            default -> {
                if (Files.size(input.file()) > 1_000_000) throw new IllegalArgumentException("文本提取最多1MB UTF-8");
                String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(input.file()))).toString();
                for (int start = 0; start < text.length(); start += 12000) add(result, "text_block", text.substring(start, Math.min(text.length(), start + 12000)));
            }
        }
        if (result.isEmpty()) add(result, "empty", ""); return result;
    }

    private static void add(List<Unit> units, String kind, String text) {
        if (Thread.currentThread().isInterrupted()) throw new com.agentops.model.ModelPort.Cancelled();
        if (text.length() > 100000 || units.stream().mapToLong(u -> u.text().length()).sum() + text.length() > 1_000_000)
            throw new IllegalArgumentException("提取文字超出100万字或单片段10万字上限，请先拆分文档");
        units.add(new Unit(units.size() + 1, kind, text));
    }
}
