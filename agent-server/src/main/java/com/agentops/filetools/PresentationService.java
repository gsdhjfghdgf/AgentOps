package com.agentops.filetools;

import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.ResultTypes.ArtifactRef;
import com.agentops.infra.Json;
import com.agentops.tool.ToolRegistry.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xslf.usermodel.*;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

/** Structured PPTX authoring with editable text, tables and charts, plus independent inspection/rendering. */
@Service
public class PresentationService {
    private static final int WIDTH = 960, HEIGHT = 540;
    private static final String FONT = "Microsoft YaHei";
    private final FileToolSupport files;
    private final Json json;
    private final PresentationLayoutRenderer renderer;
    private static final Set<String> SLIDE_FIELDS=Set.of("layout","title","subtitle","bullets","rows","chart","imageArtifactId","caption","notes","sources","metric","label","body","leftTitle","left","rightTitle","right","steps","imageSide","imageFit");

    public PresentationService(FileToolSupport files, Json json) { this.files = files; this.json = json; this.renderer = new PresentationLayoutRenderer(files); }

    public Map<String, Object> templates() {
        return Map.of("themes", PresentationTheme.ORDERED_NAMES, "themeCatalog", PresentationTheme.catalog(),
                "themeSelection","用户指定风格时优先遵循；未指定时AI结合主题、受众与用途，从themeCatalog选择最合适的一套并设置theme，简短说明选择理由，不必追问。可传purpose/audience辅助判断。theme省略或auto时后端按主题关键词回退；无匹配采用ink。整份文稿保持同一风格。",
                "layouts", List.of("cover", "section", "statement", "metric", "comparison", "process", "timeline", "image_split", "chart", "table", "image", "bullets"),
                "limits", "1-40页；标题40字；正文6条，每条100字；表格最多8行5列；图表最多8类3序列。超量需拆页，不静默截断。",
                "example", json.tree("""
                {"theme":"tech","slides":[
                  {"layout":"cover","title":"项目介绍","subtitle":"目标与当前进度","notes":"开场讲解"},
                  {"layout":"bullets","title":"核心能力","bullets":["完成真实任务","保留执行证据"],"sources":["内部项目说明"]},
                  {"layout":"table","title":"里程碑","rows":[["事项","状态"],["开发","完成"],["验收","待核实"]]},
                  {"layout":"chart","title":"验证样例","chart":{"type":"bar","categories":["第一组","第二组"],"series":[{"name":"数量","values":[12,18]}]}}
                ]}
                """), "layoutFields",Map.ofEntries(
                        Map.entry("cover","title,subtitle,可选imageArtifactId"),Map.entry("section/statement","title,body(主句最多80字),caption(补充说明)"),
                        Map.entry("metric","title,metric(简短数字及单位),label,body(口径/限定),caption"),
                        Map.entry("comparison","title,leftTitle,left(1～4条),rightTitle,right(1～4条)"),
                        Map.entry("process","title,steps:[{title,body}]，2～4步，标题宜8字以内"),
                        Map.entry("timeline","title,steps:[{date,title,body}]，2～4个时间点"),
                        Map.entry("image_split","title,imageArtifactId,body,imageSide:left/right,caption,imageFit:contain/cover，默认contain保留全图；装饰照片可cover裁剪，不裁图表或Logo"),
                        Map.entry("legacy","bullets用bullets；table用rows；chart用chart；image用imageArtifactId/caption，沿用示例")),
                "designGuide","根据主题、受众、用途选择themeCatalog中的风格，用户已指定时沿用。先做逐页分镜：每页一个主要观点、匹配证据及版式、需要的图片。6页以上至少使用4种布局，不要连续3页同一种正文。重点数字用metric；对照用comparison；流程用process；阶段用timeline；图片用image_split；保留图表单位与数据口径。正文建议3条以内、每条45字以内，细节和来源放notes。图片可用image_search/image_download或用户已有对象；图片应解释内容，避免堆装饰。",
                "workflow", "先核对来源并规划分镜，再分批create/append；最终inspect检查结构及designReview提示，render逐页预览；有问题重新排版或update。designReview仅规则检查，不能声称视觉模型已审阅。");
    }

    public ArtifactRef create(Principal owner, ToolContext context, String title, String deckJson) throws Exception {
        JsonNode input = deckSpecification(deckJson, 40);
        String theme = PresentationTheme.resolve(input.path("theme").asText("auto"), title, input);
        JsonNode slides = input.path("slides");
        return files.output(owner, context, title + ".pptx", path -> {
            try (var deck = new XMLSlideShow()) {
                deck.setPageSize(new Dimension(WIDTH, HEIGHT));
                deck.getProperties().getCustomProperties().addProperty("AgentOpsTheme",theme);
                int page = 0;
                for (JsonNode spec : slides) {
                    FileToolSupport.check(context);
                    validateFields(spec, SLIDE_FIELDS);
                    renderer.addSlide(owner, deck, spec, theme, ++page);
                }
                var check = inspect(deck);
                if (!((List<?>) check.get("issues")).isEmpty()) throw new IllegalArgumentException("内容超出布局，请拆页或缩短文字：" + json.write(check.get("issues")));
                try (var out = Files.newOutputStream(path)) { deck.write(out); }
            }
        });
    }

    /** Extend a real PPTX without asking the model to regenerate all previous pages. */
    public ArtifactRef append(Principal owner, ToolContext context, String id, String title, String deckJson) throws Exception {
        var original = files.input(owner, id, "pptx");
        var input = deckSpecification(deckJson, 5);
        return files.output(owner, context, original.originalName(), path -> {
            try (var checked = FileToolSupport.office(original.file())) { /* reject unsafe packages before editing */ }
            try (var in = Files.newInputStream(original.file()); var deck = new XMLSlideShow(in)) {
                if (!deck.getPageSize().equals(new Dimension(WIDTH, HEIGHT)))
                    throw new IllegalArgumentException("追加页面只支持本工具的960×540画布");
                if (deck.getSlides().size() + input.path("slides").size() > 40)
                    throw new IllegalArgumentException("追加后的文稿不得超过40页");
                int page = deck.getSlides().size();
                String theme=inheritedTheme(deck);
                String requested=input.path("theme").asText("auto");
                if(!requested.equals("auto")&&!requested.equals(theme))
                    throw new IllegalArgumentException("追加页面须沿用原稿风格；更换整份风格请重新生成");
                for (var spec : input.path("slides")) {
                    FileToolSupport.check(context);
                    validateFields(spec, SLIDE_FIELDS);
                    renderer.addSlide(owner, deck, spec, theme, ++page);
                }
                var check = inspect(deck);
                if (!((List<?>) check.get("issues")).isEmpty())
                    throw new IllegalArgumentException("内容超出布局，请缩短文字：" + json.write(check.get("issues")));
                try (var out = Files.newOutputStream(path)) { deck.write(out); }
            }
        });
    }

    private JsonNode deckSpecification(String deckJson, int maxPages) {
        var input = object(deckJson, Set.of("theme", "slides", "purpose", "audience"));
        String theme=input.path("theme").asText("auto");
        if (!theme.equals("auto")&&!PresentationTheme.NAMES.contains(theme))
            throw new IllegalArgumentException("未知主题，请使用presentation_templates");
        text(input,"purpose",200);text(input,"audience",100);
        var slides = input.path("slides");
        if (!slides.isArray() || slides.isEmpty() || slides.size() > maxPages)
            throw new IllegalArgumentException("本批页面须为1～" + maxPages + "页");
        return input;
    }

    private String inheritedTheme(XMLSlideShow deck){
        var property=deck.getProperties().getCustomProperties().getProperty("AgentOpsTheme");
        if(property!=null&&PresentationTheme.NAMES.contains(property.getLpwstr()))return property.getLpwstr();
        Color background=deck.getSlides().get(0).getBackground().getFillColor();
        for(String name:List.of("forest","ocean","ink"))if(PresentationTheme.of(name).accent().equals(background))return name;
        return "tech";
    }

    public Map<String, Object> inspect(Principal owner, String id) throws Exception {
        return inspect(owner, id, 1);
    }

    public Map<String, Object> inspect(Principal owner, String id, int firstSlide) throws Exception {
        var input = files.input(owner, id, "pptx");
        try (var deck = new XMLSlideShow(FileToolSupport.office(input.file()))) {
            var result = new LinkedHashMap<>(inspect(deck));
            if (firstSlide < 1 || firstSlide > deck.getSlides().size()) throw new IllegalArgumentException("firstSlide页码不存在");
            var slides = (List<?>) result.get("slides"); int last = Math.min(firstSlide + 4, slides.size());
            result.put("slides", slides.subList(firstSlide - 1, last)); result.put("firstSlide", firstSlide);
            result.put("nextSlide", last < slides.size() ? last + 1 : 0);
            var issues = (List<?>) result.get("issues"); result.put("issueCount", issues.size());
            result.put("issues", issues.subList(0, Math.min(40, issues.size())));
            result.put("issuesLimited", issues.size() > 40);
            return result;
        }
    }

    Map<String, Object> inspect(XMLSlideShow deck) {
        if (deck.getSlides().isEmpty() || deck.getSlides().size() > 100) throw new IllegalArgumentException("检查支持1～100页");
        var issues = new ArrayList<Map<String, Object>>(); var slides = new ArrayList<Map<String, Object>>();
        var size = deck.getPageSize();
        if (size.width < 1 || size.height < 1 || size.width > 4000 || size.height > 4000) throw new IllegalArgumentException("幻灯片画布尺寸无效或过大");
        for (var slide : deck.getSlides()) {
            if (slide.getShapes().size() > 300) throw new IllegalArgumentException("单页形状过多，请拆分后检查");
            var shapes = new ArrayList<Map<String, Object>>();
            int textCount = 0;
            for (var shape : slide.getShapes()) {
                var rect = shape.getAnchor(); int page = slide.getSlideNumber();
                if (!Double.isFinite(rect.getX() + rect.getY() + rect.getWidth() + rect.getHeight()) || rect.getMinX() < -1 || rect.getMinY() < -1 || rect.getMaxX() > size.width + 1 || rect.getMaxY() > size.height + 1)
                    issues.add(Map.of("slide", page, "shapeId", shape.getShapeId(), "issue", "out_of_bounds"));
                if (shape instanceof XSLFTextShape text && !text.getText().isBlank()) {
                    textCount++;
                    boolean overflow = text.getTextHeight() > rect.getHeight() + 3;
                    if (overflow) issues.add(Map.of("slide", page, "shapeId", shape.getShapeId(), "issue", "text_overflow"));
                    if (shapes.size() < 8) shapes.add(Map.of("shapeId", shape.getShapeId(), "name", clip(shape.getShapeName(), 40), "text", clip(text.getText(), 100)));
                }
                if (shape instanceof XSLFTable table) {
                    for (var row : table.getRows()) for (var cell : row.getCells()) {
                        if (cell.getTextHeight() > row.getHeight() + 2) issues.add(Map.of("slide", page, "issue", "table_text_overflow"));
                    }
                }
            }
            slides.add(Map.of("slide", slide.getSlideNumber(), "shapes", shapes, "textShapesLimited", textCount > 8, "textShapeCount", textCount));
        }
        return Map.of("theme", inheritedTheme(deck), "slideCount", slides.size(), "width", size.width, "height", size.height, "issues", issues,
                "slides", slides, "structurallyReadable", true,
                "designReview",PresentationDesignReview.review(deck),
                "notice", "仅结构和文本几何检查。表述、引用、数字真实性仍须核验；预览由POI渲染，与Office可能略有差异。");
    }

    public ArtifactRef update(Principal owner, ToolContext context, String id, String title, String changesJson) throws Exception {
        var input = files.input(owner, id, "pptx"); var changes = json.tree(changesJson);
        if (!changes.isArray() || changes.isEmpty() || changes.size() > 50) throw new IllegalArgumentException("changesJson须为1～50条修改");
        return files.output(owner, context, input.originalName(), path -> {
            try (var checked = FileToolSupport.office(input.file())) { /* validate before opening the writable copy */ }
            try (var in = Files.newInputStream(input.file()); var deck = new XMLSlideShow(in)) {
                for (var change : changes) {
                    FileToolSupport.check(context); validateFields(change, Set.of("slide", "shapeId", "text"));
                    int page = positiveInteger(change, "slide"), shapeId = positiveInteger(change, "shapeId");
                    if (page > deck.getSlides().size()) throw new IllegalArgumentException("幻灯片页码不存在");
                    var shape = deck.getSlides().get(page - 1).getShapes().stream().filter(s -> s.getShapeId() == shapeId).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("shapeId不存在，请先inspect"));
                    if (!(shape instanceof XSLFTextShape text)) throw new IllegalArgumentException("仅支持已检查的文本框，不替换图片或图表");
                    String content = text(change, "text", 2000);
                    // Keep the first run's styling while replacing the selected text only.
                    var first = text.getTextParagraphs().isEmpty() ? null : text.getTextParagraphs().get(0).getTextRuns().stream().findFirst().orElse(null);
                    String family = first == null ? FONT : first.getFontFamily(); Double size = first == null ? 24.0 : first.getFontSize();
                    boolean bold = first != null && first.isBold(), italic = first != null && first.isItalic();
                    var paint = first == null ? null : first.getFontColor();
                    Color color = paint instanceof org.apache.poi.sl.usermodel.PaintStyle.SolidPaint solid
                            ? org.apache.poi.sl.draw.DrawPaint.applyColorTransform(solid.getSolidColor()) : null;
                    text.setText(content);
                    for(var paragraph:text.getTextParagraphs())for(var replacement:paragraph.getTextRuns()){
                        replacement.setFontFamily(family == null ? FONT : family);replacement.setFontFamily(family == null ? FONT : family,org.apache.poi.common.usermodel.fonts.FontGroup.EAST_ASIAN);replacement.setFontSize(size == null ? 24.0 : size);
                        replacement.setBold(bold);replacement.setItalic(italic);if(color!=null)replacement.setFontColor(color);
                    }
                }
                var result = inspect(deck);
                if (!((List<?>) result.get("issues")).isEmpty()) throw new IllegalArgumentException("修改后存在布局问题，请缩短文字或重建相关页面");
                try (var out = Files.newOutputStream(path)) { deck.write(out); }
            }
        });
    }

    public ArtifactRef render(Principal owner, ToolContext context, String id, int page) throws Exception {
        var input = files.input(owner, id, "pptx");
        return files.output(owner, context, "幻灯片-" + page + ".png", path -> renderTo(input.file(), page, path));
    }

    public void renderTo(Path source, int page, Path target) throws Exception {
        try (var deck = new XMLSlideShow(FileToolSupport.office(source))) {
            if (page < 1 || page > deck.getSlides().size()) throw new IllegalArgumentException("页码不存在");
            inspect(deck); var size = deck.getPageSize();
            int width = 1440, height = (int) Math.ceil(size.height * (double) width / size.width);
            FileToolSupport.dimensions(width, height);
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB); var graphics = image.createGraphics();
            try {
                graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                graphics.scale((double) width / size.width, (double) width / size.width);
                var slide = deck.getSlides().get(page - 1);
                var slideGraphics = (Graphics2D) graphics.create();
                try { slide.draw(slideGraphics); } finally { slideGraphics.dispose(); }
                for (var shape : slide.getShapes()) if (shape instanceof XSLFGraphicFrame frame && frame.hasChart())
                    NativeChartPreview.draw(graphics, frame.getAnchor(), frame.getChart());
            } finally { graphics.dispose(); }
            ImageIO.write(image, "png", target.toFile());
        }
    }

    static JsonNode object(String source, Set<String> fields) { return validateFields(new Json().tree(source), fields); }
    static JsonNode validateFields(JsonNode node, Set<String> fields) {
        if (!node.isObject()) throw new IllegalArgumentException("需要JSON对象");
        node.fieldNames().forEachRemaining(key -> { if (!fields.contains(key)) throw new IllegalArgumentException("未知字段：" + key); });
        return node;
    }
    static String text(JsonNode node, String key, int max) { return node.has(key) ? scalar(node.get(key), max) : ""; }
    static String requiredText(JsonNode node, String key, int max) {
        String value = text(node, key, max); if (value.isBlank()) throw new IllegalArgumentException(key + "不能为空"); return value;
    }
    static String scalar(JsonNode value, int max) {
        if (!value.isTextual() && !value.isNumber() && !value.isBoolean()) throw new IllegalArgumentException("内容须为文字、数字或布尔值");
        String text = value.asText();
        if (text.length() > max || text.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\t')) throw new IllegalArgumentException("文字过长或包含非法控制字符，请拆页");
        return text;
    }
    static int positiveInteger(JsonNode node, String key) {
        if (!node.path(key).isIntegralNumber() || !node.path(key).canConvertToInt() || node.path(key).asInt() < 1)
            throw new IllegalArgumentException(key + "须为正整数");
        return node.path(key).asInt();
    }
    static String clip(String text, int max) { return text.substring(0, Math.min(text.length(), max)); }
}
