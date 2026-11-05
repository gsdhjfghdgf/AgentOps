package com.agentops.filetools;

import com.agentops.artifact.ArtifactService;
import com.agentops.domain.ResultTypes.ArtifactRef;
import com.agentops.domain.RunMode;
import com.agentops.infra.Json;
import com.agentops.persistence.ArtifactRepo;
import com.agentops.persistence.RunRepo;
import com.agentops.project.ProjectService;
import com.agentops.tool.ToolRegistry;
import com.agentops.tool.ToolRegistry.ToolContext;
import com.agentops.tool.WorkspaceService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FileToolsTest {
    @TempDir Path temp;
    final Json json = new Json();
    final ArtifactService artifacts = mock(ArtifactService.class);
    final RunRepo runs = mock(RunRepo.class);
    final ArtifactRepo repo = mock(ArtifactRepo.class);
    final ProjectService projects = mock(ProjectService.class);
    final ToolContext context = new ToolContext(RUN, UUID.randomUUID(), "file-test", null);
    final Map<String, ArtifactService.Resolved> stored = new HashMap<>();
    FileToolSupport files; PresentationService presentations; SpreadsheetService spreadsheets;
    DocumentReadService documents; MediaArchiveService media;

    @BeforeEach void setup() throws Exception {
        files = new FileToolSupport(artifacts, repo, runs, projects, new WorkspaceService(temp.resolve("workspace").toString()));
        presentations = new PresentationService(files, json); spreadsheets = new SpreadsheetService(files, json);
        documents = new DocumentReadService(files); media = new MediaArchiveService(files, json);
        when(runs.findByIdOwner(OWNER, RUN)).thenReturn(Optional.of(rowWithConfig("{}")));
        when(artifacts.stagingPath(eq(OWNER.tenantId()), any(), anyString())).thenAnswer(i -> temp.resolve(i.getArgument(1) + "." + i.getArgument(2)));
        when(artifacts.resolveForDownload(eq(OWNER), any())).thenAnswer(i -> Optional.ofNullable(stored.get(i.getArgument(1).toString())));
        when(artifacts.register(eq(OWNER), eq(SESSION), eq(RUN), anyString(), anyString(), any(), eq("output"))).thenAnswer(i -> {
            String id = UUID.randomUUID().toString(), name = i.getArgument(3), mime = i.getArgument(4); Path source = i.getArgument(5);
            Path target = temp.resolve(id + "." + FileToolSupport.extension(name)); Files.move(source, target);
            long size = Files.size(target); stored.put(id, new ArtifactService.Resolved(target, name, mime, size));
            return new ArtifactRef(id, name, mime, size, FileToolSupport.sha256(target));
        });
    }

    private String input(Path path, String name) throws Exception {
        String id = UUID.randomUUID().toString();
        stored.put(id, new ArtifactService.Resolved(path, name, FileToolSupport.mime(FileToolSupport.extension(name)), Files.size(path))); return id;
    }

    private ArtifactRef deck() throws Exception {
        return presentations.create(OWNER, context, "项目介绍", """
                {"theme":"forest","slides":[
                  {"layout":"cover","title":"AgentOps","subtitle":"项目介绍","notes":"开场讲解"},
                  {"layout":"bullets","title":"核心功能","bullets":["读取项目文件","保留阶段证据"],"sources":["内部验收记录"]},
                  {"layout":"table","title":"计划","rows":[["事项","状态"],["开发","完成"]]},
                  {"layout":"chart","title":"数量","chart":{"type":"bar","categories":["一组","二组"],"series":[{"name":"验证样例","values":[12,18]}]}}
                ]}
                """);
    }

    @Test void techDeckHasTwelveDistinctEditableCompositionsAndFaithfulBlueChartPreview() throws Exception {
        var photo=temp.resolve("photo.png");ImageIO.write(new BufferedImage(1200,700,BufferedImage.TYPE_INT_RGB),"png",photo.toFile());
        String picture=input(photo,"photo.png");
        var specs=List.of(
                Map.of("layout","cover","title","人工智能与生活","subtitle","证据、机会与边界","imageArtifactId",picture),
                Map.of("layout","section","title","第一章","body","AI已经进入高频场景","caption","从日常应用看实际影响"),
                Map.of("layout","statement","title","核心判断","body","把任务交给AI，把判断留给人"),
                Map.of("layout","metric","title","数据样例","metric","40%","label","示例指标","body","仅用于排版验收，不是事实主张"),
                Map.of("layout","comparison","title","机会与约束","leftTitle","机会","left",List.of("更快整理资料","辅助生成初稿"),"rightTitle","约束","right",List.of("核对数字口径","保留人工判断")),
                Map.of("layout","process","title","执行流程","steps",List.of(Map.of("title","检索","body","定位原始资料"),Map.of("title","核验","body","检查来源和日期"),Map.of("title","交付","body","制作可编辑文件"))),
                Map.of("layout","timeline","title","迭代计划","steps",List.of(Map.of("date","阶段一","title","验证","body","完成原型"),Map.of("date","阶段二","title","交付","body","完善流程"))),
                Map.of("layout","image_split","title","图文叙事","body","用图片解释一个具体场景，保留简短说明。","imageArtifactId",picture),
                Map.of("layout","chart","title","样例对比","chart",Map.of("categories",List.of("A","B"),"series",List.of(Map.of("name","样例","values",List.of(12,18))))),
                Map.of("layout","table","title","可编辑表格","rows",List.of(List.of("项目","状态"),List.of("排版","待审阅"))),
                Map.of("layout","image","title","图片原貌","imageArtifactId",picture,"caption","测试素材"),
                Map.of("layout","bullets","title","关键事项","bullets",List.of("每页一个主要观点","数据图表保留口径","预览后再交付")));
        var output=presentations.create(OWNER,context,"科技风验收",json.write(Map.of("theme","tech","slides",specs)));
        var review=presentations.inspect(OWNER,output.objectId());assertThat(review.get("slideCount")).isEqualTo(12);assertThat((List<?>)review.get("issues")).isEmpty();
        try(var ppt=new XMLSlideShow(Files.newInputStream(stored.get(output.objectId()).file()))){
            assertThat(ppt.getSlides()).extracting(s->s.getXmlObject().getCSld().getName()).doesNotHaveDuplicates();
            assertThat(ppt.getSlides().get(0).getShapes()).anyMatch(s->s instanceof XSLFPictureShape);
            assertThat(ppt.getSlides().get(8).getShapes()).anyMatch(s->s instanceof XSLFGraphicFrame f&&f.hasChart());
        }
        var rendered=presentations.render(OWNER,context,output.objectId(),9);var bitmap=ImageIO.read(stored.get(rendered.objectId()).file().toFile());long blue=0;
        for(int x=150;x<1300;x+=5)for(int y=200;y<670;y+=5){var color=new Color(bitmap.getRGB(x,y));if(color.getBlue()>color.getRed()*1.5&&color.getBlue()>color.getGreen())blue++;}
        assertThat(blue).as("preview must honor the native tech chart palette").isGreaterThan(300);
    }

    @Test void appendWithoutThemeInheritsTheOriginalDesignAndRulesAreNotVisionClaims() throws Exception {
        var original=presentations.create(OWNER,context,"主题", "{\"theme\":\"tech\",\"slides\":[{\"layout\":\"cover\",\"title\":\"标题\"}]}");
        var next=presentations.append(OWNER,context,original.objectId(),"主题-续页","{\"slides\":[{\"layout\":\"metric\",\"title\":\"样例\",\"metric\":\"42\",\"label\":\"测试数字\"}]}");
        try(var ppt=new XMLSlideShow(Files.newInputStream(stored.get(next.objectId()).file()))){assertThat(ppt.getSlides().get(1).getXmlObject().getCSld().getName()).isEqualTo("AgentOps|tech|metric");}
        var report=(Map<?,?>)presentations.inspect(OWNER,next.objectId()).get("designReview");assertThat(report.get("visionReviewed")).isEqualTo(false);assertThat(report.get("mode")).isEqualTo("rules_only");
    }

    @ParameterizedTest
    @CsvSource({"论文答辩,academic", "季度销售报告,business", "生态保护,forest", "张家界旅游,ocean", "AI对生活的影响,warm", "历史人物,editorial", "创意营销,vivid", "AgentOps产品介绍,tech", "会议说明,ink"})
    void omittedThemeSelectsARelevantFallbackAndExplicitStyleTakesPriority(String title,String expected) throws Exception {
        var ref=presentations.create(OWNER,context,title,"{\"slides\":[{\"layout\":\"cover\",\"title\":\"主题\"}]}");
        assertThat(presentations.inspect(OWNER,ref.objectId()).get("theme")).isEqualTo(expected);
        var explicit=presentations.create(OWNER,context,title,"{\"theme\":\"tech\",\"slides\":[{\"layout\":\"cover\",\"title\":\"主题\"}]}");
        assertThat(presentations.inspect(OWNER,explicit.objectId()).get("theme")).isEqualTo("tech");
    }

    @Test void styleChoiceCanUsePurposeAndAudienceWithoutChangingAppendStyle() throws Exception {
        var ref=presentations.create(OWNER,context,"介绍","{\"theme\":\"auto\",\"purpose\":\"论文答辩\",\"audience\":\"研究人员\",\"slides\":[{\"layout\":\"cover\",\"title\":\"介绍\"}]}");
        assertThat(presentations.inspect(OWNER,ref.objectId()).get("theme")).isEqualTo("academic");
        var next=presentations.append(OWNER,context,ref.objectId(),"介绍-6页","{\"theme\":\"auto\",\"slides\":[{\"title\":\"发现\",\"bullets\":[\"结果\"]}]}");
        assertThat(next.originalName()).isEqualTo("介绍.pptx");
        assertThat(presentations.inspect(OWNER,next.objectId()).get("theme")).isEqualTo("academic");
        int count=stored.size();
        assertThatThrownBy(()->presentations.append(OWNER,context,next.objectId(),"介绍-10页","{\"theme\":\"vivid\",\"slides\":[{\"title\":\"发现\",\"bullets\":[\"结果\"]}]}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("沿用原稿风格");
        assertThat(stored).hasSize(count);
    }

    @ParameterizedTest
    @ValueSource(strings={"tech","business","academic","editorial","warm","vivid","forest","ocean","ink"})
    void everyCatalogStyleProducesAnEditableDeckAndLegiblePreview(String theme) throws Exception {
        var ref=presentations.create(OWNER,context,"样例",json.write(Map.of("theme",theme,"slides",List.of(
                Map.of("layout","cover","title","AgentOps 项目介绍","subtitle","根据主题与用途选择风格"),
                Map.of("layout","metric","title","一种风格，完整交付","metric","1","label","最终演示文稿","body","在同一个文件里逐页查看")))));
        assertThat(presentations.inspect(OWNER,ref.objectId()).get("issues")).isEqualTo(List.of());
        try(var deck=new XMLSlideShow(Files.newInputStream(stored.get(ref.objectId()).file()))) {
            assertThat(deck.getSlides()).hasSize(2);
            assertThat(deck.getProperties().getCustomProperties().getProperty("AgentOpsTheme").getLpwstr()).isEqualTo(theme);
            var slide=deck.getSlides().get(0);
            var title=(XSLFTextShape)slide.getShapes().stream().filter(s->s.getShapeName().equals("title")).findFirst().orElseThrow();
            var paint=(org.apache.poi.sl.usermodel.PaintStyle.SolidPaint)title.getTextParagraphs().get(0).getTextRuns().get(0).getFontColor();
            assertThat(org.apache.poi.sl.draw.DrawPaint.applyColorTransform(paint.getSolidColor())).isNotEqualTo(slide.getBackground().getFillColor());
        }
        Path preview=Path.of("target","presentation-themes",theme+".png");Files.createDirectories(preview.getParent());
        presentations.renderTo(stored.get(ref.objectId()).file(),1,preview);
        assertThat(ImageIO.read(preview.toFile()).getWidth()).isEqualTo(1440);
    }

    @Test void designReviewReportsRealDensityAndRepeatedLayouts() throws Exception {
        var spec=Map.of("layout","bullets","title","重复版式","bullets",List.of("第一条","第二条"));
        var output=presentations.create(OWNER,context,"重复",json.write(Map.of("slides",Collections.nCopies(6,spec))));
        var review=(Map<?,?>)presentations.inspect(OWNER,output.objectId()).get("designReview");
        assertThat(json.write(review)).contains("repeated_layout","limited_layout_variety");
    }

    @Test void everyParagraphReceivesTheChosenFontAndColorIncludingEastAsianText() throws Exception {
        var output=presentations.create(OWNER,context,"多行字体", "{\"theme\":\"tech\",\"slides\":[{\"layout\":\"cover\",\"title\":\"AgentOps\\n科技产品风\",\"subtitle\":\"第一行\\n第二行\"}]}");
        try(var ppt=new XMLSlideShow(Files.newInputStream(stored.get(output.objectId()).file()))){
            var title=(XSLFTextShape)ppt.getSlides().get(0).getShapes().stream().filter(s->s.getShapeName().equals("title")).findFirst().orElseThrow();
            assertThat(title.getTextParagraphs()).hasSize(2);
            for(var paragraph:title.getTextParagraphs())for(var run:paragraph.getTextRuns()){
                assertThat(run.getFontSize()).isEqualTo(56.0);assertThat(run.getFontFamily()).isEqualTo("Microsoft YaHei");
                assertThat(run.getFontFamily(org.apache.poi.common.usermodel.fonts.FontGroup.EAST_ASIAN)).isEqualTo("Microsoft YaHei");
                assertThat(org.apache.poi.sl.draw.DrawPaint.applyColorTransform(((org.apache.poi.sl.usermodel.PaintStyle.SolidPaint)run.getFontColor()).getSolidColor())).isEqualTo(Color.WHITE);
            }
        }
    }

    @Test void appendedPagesProduceACompleteNewPptxAndPreserveOriginalChartsAndNotes() throws Exception {
        var original = deck();
        byte[] oldBytes = Files.readAllBytes(stored.get(original.objectId()).file());
        var current = original;
        for (int page = 5; page <= 12; page++) {
            current = presentations.append(OWNER, context, current.objectId(), "完整文稿-" + page,
                    json.write(Map.of("theme", "forest", "slides", List.of(Map.of("layout", "bullets", "title", "页面" + page, "bullets", List.of("分批添加的真实内容"))))));
        }
        assertThat(current.objectId()).isNotEqualTo(original.objectId());
        assertThat(Files.readAllBytes(stored.get(original.objectId()).file())).isEqualTo(oldBytes);
        assertThat(presentations.inspect(OWNER, current.objectId()).get("slideCount")).isEqualTo(12);
        try (var ppt = new XMLSlideShow(Files.newInputStream(stored.get(current.objectId()).file()))) {
            assertThat(ppt.getSlides().get(3).getShapes()).anyMatch(s -> s instanceof XSLFGraphicFrame f && f.hasChart());
            assertThat(ppt.getSlides().get(0).getNotes().getShapes()).filteredOn(s -> s instanceof XSLFTextShape)
                    .anyMatch(s -> ((XSLFTextShape) s).getText().contains("开场讲解"));
        }
        var image = presentations.render(OWNER, context, current.objectId(), 12);
        assertThat(ImageIO.read(stored.get(image.objectId()).file().toFile()).getWidth()).isGreaterThan(500);
    }

    @Test void appendingRejectsEmptyOversizedOrForeignInputsWithoutRegisteringNewFiles() throws Exception {
        var original = deck(); int before = stored.size();
        var page = Map.of("layout", "bullets", "title", "页面", "bullets", List.of("正文"));
        assertThatThrownBy(() -> presentations.append(OWNER, context, original.objectId(), "无效", "{\"slides\":[]}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> presentations.append(OWNER, context, original.objectId(), "无效", json.write(Map.of("slides", Collections.nCopies(6, page)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> presentations.append(OWNER, context, UUID.randomUUID().toString(), "无效", json.write(Map.of("slides", List.of(page)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(stored).hasSize(before);
    }

    @Test void pptxKeepsEditableShapesChartsNotesAndCanBeRendered() throws Exception {
        var ref = deck();
        try (var ppt = new XMLSlideShow(Files.newInputStream(stored.get(ref.objectId()).file()))) {
            assertThat(ppt.getSlides()).hasSize(4);
            assertThat(ppt.getSlides().get(0).getBackground().getFillColor()).isEqualTo(Color.decode("#27533D"));
            assertThat(ppt.getSlides().get(1).getBackground().getFillColor()).isEqualTo(Color.decode("#F7F8F5"));
            assertThat(ppt.getSlides().get(2).getShapes()).anyMatch(s -> s instanceof XSLFTable);
            assertThat(ppt.getSlides().get(3).getShapes()).anyMatch(s -> s instanceof XSLFGraphicFrame f && f.hasChart());
            var frame = (XSLFGraphicFrame) ppt.getSlides().get(3).getShapes().get(1);
            assertThat(frame.getAnchor().getX()).isEqualTo(60); assertThat(frame.getAnchor().getY()).isEqualTo(136);
            assertThat(ppt.getSlides().get(0).getNotes().getShapes()).filteredOn(s -> s instanceof XSLFTextShape)
                    .anyMatch(s -> ((XSLFTextShape) s).getText().contains("开场讲解"));
        }
        var check = presentations.inspect(OWNER, ref.objectId());
        assertThat(check.get("slideCount")).isEqualTo(4); assertThat((List<?>) check.get("issues")).isEmpty();
        var preview = presentations.render(OWNER, context, ref.objectId(), 4);
        if (System.getProperty("filetools.proof-dir") != null) {
            Path proof = Path.of(System.getProperty("filetools.proof-dir")); Files.createDirectories(proof);
            Files.copy(stored.get(preview.objectId()).file(), proof.resolve("chart.png"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(stored.get(ref.objectId()).file(), proof.resolve("sample.pptx"), StandardCopyOption.REPLACE_EXISTING);
        }
        var image = ImageIO.read(stored.get(preview.objectId()).file().toFile());
        assertThat(image.getWidth()).isEqualTo(1440);
        assertThat(image.getRGB(800,50) & 0xFFFFFF).as("chart preview must not paint over the slide title area").isEqualTo(0xF7F8F5);
        long colored = 0;
        for (int x = 200; x < 1300; x += 5) for (int y = 200; y < 670; y += 5) {
            var color = new Color(image.getRGB(x, y)); if (color.getGreen() > color.getRed() * 1.2 && color.getGreen() > color.getBlue()) colored++;
        }
        assertThat(colored).as("chart bars must appear, not an empty chart frame").isGreaterThan(300);
    }

    @Test void pptxUpdateUsesRealShapeIdsAndPreservesOriginal() throws Exception {
        var original = deck(); Path source = stored.get(original.objectId()).file(); String before = FileToolSupport.sha256(source);
        int id;
        try (var ppt = new XMLSlideShow(Files.newInputStream(source))) { id = ppt.getSlides().get(1).getShapes().get(0).getShapeId(); }
        var updated = presentations.update(OWNER, context, original.objectId(), "修订", "[{\"slide\":2,\"shapeId\":" + id + ",\"text\":\"更新后的功能\"}]");
        assertThat(documents.read(OWNER, updated.objectId(), 2, 0).get("text").toString()).contains("更新后的功能");
        try (var ppt = new XMLSlideShow(Files.newInputStream(stored.get(updated.objectId()).file()))) {
            var title = (XSLFTextShape) ppt.getSlides().get(1).getShapes().get(0);
            assertThat(title.getTextParagraphs().get(0).getTextRuns().get(0).isBold()).isTrue();
        }
        assertThat(FileToolSupport.sha256(source)).isEqualTo(before);
    }

    @Test void badPptArgumentsDoNotCreateArtifactsOrSilentlyTruncate() {
        assertThatThrownBy(() -> presentations.create(OWNER, context, "超长", "{\"slides\":[{\"title\":\"标题\",\"bullets\":[\"" + "字".repeat(101) + "\"]}]}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("拆页");
        assertThatThrownBy(() -> presentations.create(OWNER, context, "参数", "{\"slides\":[],\"unknown\":true}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("未知字段");
        assertThat(stored).isEmpty();
    }

    @Test void officeExternalImagesAreRejectedBeforeAnyNetworkAccess() throws Exception {
        Path path = temp.resolve("external.pptx");
        try (var ppt = new XMLSlideShow()) {
            ppt.createSlide().getPackagePart().addExternalRelationship("http://127.0.0.1/private", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image");
            try (var out = Files.newOutputStream(path)) { ppt.write(out); }
        }
        String id = input(path, "external.pptx");
        assertThatThrownBy(() -> presentations.inspect(OWNER, id)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("外部资源");
    }

    @Test void immutableArtifactAccessRemainsOwnerScoped() throws Exception {
        var ref = deck();
        var other = com.agentops.domain.Primitives.Principal.of(OWNER.tenantId(), UUID.randomUUID());
        when(artifacts.resolveForDownload(eq(other), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> files.info(other, ref.objectId())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("无权限");
    }

    @Test void spreadsheetsKeepTypesAndFormulaLikeTextWithoutExecution() throws Exception {
        var ref = spreadsheets.create(OWNER, context, "明细", "[{\"name\":\"数据\",\"rows\":[[\"名称\",\"数值\"],[\"=HYPERLINK(1)\",0.1],[true,0.2]]}]");
        try (var book = new XSSFWorkbook(Files.newInputStream(stored.get(ref.objectId()).file()))) {
            assertThat(book.getSheetAt(0).getRow(1).getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
            assertThat(book.getSheetAt(0).getRow(2).getCell(0).getBooleanCellValue()).isTrue();
        }
        var stats = spreadsheets.analyze(OWNER, ref.objectId(), "数据", "数值");
        assertThat(stats).containsEntry("sum", "0.3").containsEntry("mean", "0.15").containsEntry("numericCount", 2);
        assertThat(spreadsheets.read(OWNER, ref.objectId(), "数据", "A1:B3").get("rows")).asList().hasSize(3);
    }

    @Test void statisticsReportInvalidMissingAndFormulaRowsSeparately() throws Exception {
        Path path = temp.resolve("formula.xlsx");
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("数据"); sheet.createRow(0).createCell(0).setCellValue("数量");
            sheet.createRow(1).createCell(0).setCellValue(10); sheet.createRow(2).createCell(0).setCellValue("invalid");
            sheet.createRow(4).createCell(0).setCellFormula("SUM(A2:A4)");
            try (var out = Files.newOutputStream(path)) { book.write(out); }
        }
        var stats = spreadsheets.analyze(OWNER, input(path, "formula.xlsx"), "数据", "数量");
        assertThat(stats).containsEntry("numericCount", 1).containsEntry("missingCount", 1).containsEntry("invalidCount", 1).containsEntry("formulaCount", 1).containsEntry("sum", "10.0");
    }

    @Test void spreadsheetLimitsRejectInsteadOfTruncating() throws Exception {
        assertThatThrownBy(() -> spreadsheets.create(OWNER, context, "编号", "[{\"name\":\"数据\",\"rows\":[[12345678901234567]]}]"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("15位");
        var ref = spreadsheets.create(OWNER, context, "数据", "[{\"name\":\"数据\",\"rows\":[[\"数量\"],[1]]}]");
        assertThatThrownBy(() -> spreadsheets.read(OWNER, ref.objectId(), "", "A1:Z10000")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1000");
    }

    @Test void wordSearchAndReadKeepSourceLocations() throws Exception {
        Path path = temp.resolve("doc.docx");
        try (var doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("任务来源：内部项目文档。");
            doc.createTable(1, 1).getRow(0).getCell(0).setText("测试结论：尚未验收。");
            try (var out = Files.newOutputStream(path)) { doc.write(out); }
        }
        String id = input(path, "来源.docx");
        assertThat(documents.read(OWNER, id, 1, 0)).containsEntry("unitKind", "paragraph").containsEntry("text", "任务来源：内部项目文档。");
        var found = json.tree(json.write(documents.search(OWNER, id, "尚未验收"))).path("matches").get(0);
        assertThat(found.path("unit").asInt()).isEqualTo(2); assertThat(found.path("excerpt").asText()).contains("尚未验收");
    }

    private String pdf(String... content) throws Exception {
        Path path = temp.resolve(UUID.randomUUID() + ".pdf");
        try (var doc = new PDDocument()) {
            for (String text : content) {
                var page = new PDPage(); doc.addPage(page);
                try (var stream = new PDPageContentStream(doc, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 18);
                    stream.newLineAtOffset(70, 700); stream.showText(text); stream.endText();
                }
            }
            doc.save(path.toFile());
        }
        return input(path, "原文.pdf");
    }

    @Test void pdfPagesAreExtractedMergedAndRenderedInActualOrder() throws Exception {
        String source = pdf("first", "second", "third");
        var part = media.pdfExtract(OWNER, context, source, "节选", 2, 3);
        var merged = media.pdfMerge(OWNER, context, "合并", json.write(List.of(part.objectId(), source)));
        try (var doc = Loader.loadPDF(stored.get(merged.objectId()).file().toFile())) {
            assertThat(doc.getNumberOfPages()).isEqualTo(5);
            var font = doc.getPage(0).getResources().getFont(org.apache.pdfbox.cos.COSName.getPDFName("F1"));
            assertThat(font.getName()).isEqualTo("Helvetica");
        }
        assertThat(documents.read(OWNER, merged.objectId(), 1, 0).get("text").toString()).contains("second");
        var preview = media.pdfRender(OWNER, context, merged.objectId(), 1);
        assertThat(ImageIO.read(stored.get(preview.objectId()).file().toFile()).getHeight()).isEqualTo(1200);
        assertThatThrownBy(() -> media.pdfExtract(OWNER, context, source, "错误", 2, 9)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("范围无效");
    }

    @Test void imagesAreResizedWithoutUpscalingOrChangingTheOriginal() throws Exception {
        Path path = temp.resolve("picture.png"); var image = new BufferedImage(400, 200, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, Color.GREEN.getRGB()); ImageIO.write(image, "png", path.toFile());
        String id = input(path, "图片.png"), hash = FileToolSupport.sha256(path);
        var result = media.imageResize(OWNER, context, id, "缩略", 100, 100);
        assertThat(media.imageInfo(OWNER, result.objectId())).containsEntry("width", 100).containsEntry("height", 50).containsEntry("alpha", true);
        assertThat(FileToolSupport.sha256(path)).isEqualTo(hash);
        assertThatThrownBy(() -> FileToolSupport.dimensions(10000, 10000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void archivePreservesSameNamedInputsAndRecordsMatchingHashes() throws Exception {
        String one = pdf("one"), two = pdf("two");
        var ref = media.archive(OWNER, context, "交付", json.write(List.of(one, two)));
        try (var zip = new ZipFile(stored.get(ref.objectId()).file().toFile())) {
            assertThat(zip.getEntry("1-原文.pdf")).isNotNull(); assertThat(zip.getEntry("2-原文.pdf")).isNotNull();
            var manifest = json.tree(new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertThat(manifest.get(0).path("sha256").asText()).isEqualTo(FileToolSupport.sha256(stored.get(one).file()));
        }
        assertThat(media.archiveList(OWNER, ref.objectId()).get("entries")).asList().hasSize(3);
    }

    @Test void zipInspectionFlagsTraversalNamesWithoutExtractingAnything() throws Exception {
        Path path = temp.resolve("bad.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(path))) { zip.putNextEntry(new ZipEntry("../escape.txt")); zip.write(1); zip.closeEntry(); }
        var info = json.tree(json.write(media.archiveList(OWNER, input(path, "bad.zip"))));
        assertThat(info.path("entries").get(0).path("unsafeName").asBoolean()).isTrue(); assertThat(temp.resolve("escape.txt")).doesNotExist();
    }

    @Test void cancellationPreventsNewArtifactsAndInvalidNamesCannotChoosePaths() {
        var cancelled = new ToolContext(RUN, UUID.randomUUID(), "cancelled", new com.agentops.model.ModelPort.ExecutionControl() {
            public boolean cancellationRequested() { return true; }
            public java.time.Instant deadlineAt() { return java.time.Instant.MAX; }
        });
        assertThatThrownBy(() -> files.output(OWNER, cancelled, "test.txt", path -> Files.writeString(path, "never"))).isInstanceOf(com.agentops.model.ModelPort.Cancelled.class);
        assertThatThrownBy(() -> files.output(OWNER, context, "../escape.txt", path -> Files.writeString(path, "never"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(stored).isEmpty();
    }

    @Test void registrationsAreDistinctAndModesRetainReadOnlyBounds() {
        var registry = new ToolRegistry(); new FileTools(registry, files, presentations, spreadsheets, documents, media, json).register();
        assertThat(registry.names()).hasSize(22).contains("presentation_create", "presentation_append", "pdf_merge", "document_read", "spreadsheet_analyze");
        assertThat(RunMode.PLAN.allows("document_read")).isTrue(); assertThat(RunMode.CHAT.allows("spreadsheet_analyze")).isTrue();
        assertThat(RunMode.PLAN.allows("presentation_create")).isFalse(); assertThat(RunMode.CHAT.allows("pdf_merge")).isFalse();
    }

    @Test void workspaceImportCreatesSnapshotWithoutChangingOriginalAndRejectsTraversal() throws Exception {
        var workspace = new WorkspaceService(temp.resolve("workspace").toString());
        Path source = workspace.root(OWNER).resolve("source.txt"); Files.writeString(source, "original content");
        String hash = FileToolSupport.sha256(source);
        var imported = files.importFile(OWNER, context, "workspace", "source.txt");
        Files.writeString(source, "changed later");
        assertThat(imported.sha256()).isEqualTo(hash);
        assertThat(Files.readString(stored.get(imported.objectId()).file())).isEqualTo("original content");
        assertThatThrownBy(() -> files.importFile(OWNER, context, "workspace", "../outside.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files.importFile(OWNER, context, "system", "source.txt")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void inspectorPagesTextButStillChecksAllSlides() throws Exception {
        var slides = new ArrayList<Map<String,Object>>();
        for (int i=1;i<=8;i++) slides.add(Map.of("layout","bullets","title","第"+i+"页","bullets",List.of("文字内容")));
        var ref = presentations.create(OWNER, context, "多页", json.write(Map.of("slides",slides)));
        var first = presentations.inspect(OWNER,ref.objectId(),1);
        var second = presentations.inspect(OWNER,ref.objectId(),6);
        assertThat(first).containsEntry("slideCount",8).containsEntry("nextSlide",6).containsEntry("issueCount",0);
        assertThat((List<?>)first.get("slides")).hasSize(5); assertThat((List<?>)second.get("slides")).hasSize(3);
        assertThat(second).containsEntry("nextSlide",0);
    }

    @Test void dataChartProducesARealImageAndRejectsMismatchedSeries() throws Exception {
        var ref = media.chart(OWNER, context, "销售额", "{\"type\":\"bar\",\"labels\":[\"一月\",\"二月\"],\"values\":[-5,12],\"unit\":\"万元\"}");
        assertThat(ImageIO.read(stored.get(ref.objectId()).file().toFile()).getWidth()).isEqualTo(1400);
        assertThatThrownBy(() -> media.chart(OWNER, context, "错误", "{\"labels\":[\"A\"],\"values\":[1,2]}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("等长");
    }
}
