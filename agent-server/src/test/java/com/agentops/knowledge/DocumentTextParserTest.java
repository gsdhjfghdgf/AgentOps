package com.agentops.knowledge;
import com.agentops.api.RunApplicationService.BadRequestException;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayOutputStream;
import java.nio.charset.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DocumentTextParserTest {
    final DocumentTextParser parser=new DocumentTextParser();
    MockMultipartFile file(String name,byte[] bytes){return new MockMultipartFile("file",name,"application/octet-stream",bytes);}
    @Test void parsesUtf8AndChineseWindowsText() {
        for(var charset:new Charset[]{StandardCharsets.UTF_8,Charset.forName("GB18030")})
            assertThat(parser.parse(file("制度.txt","住宿标准600元".getBytes(charset))).text()).isEqualTo("住宿标准600元");
    }
    @Test void parsesRealDocx()throws Exception {
        byte[] bytes;
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("差旅住宿标准：每晚600元");doc.write(out);bytes=out.toByteArray();
        }
        assertThat(parser.parse(file("差旅.docx",bytes)).text()).contains("差旅住宿标准","600元");
        var fixture=java.nio.file.Path.of("target","test-fixtures");java.nio.file.Files.createDirectories(fixture);
        java.nio.file.Files.write(fixture.resolve("travel.docx"),bytes);
    }
    @Test void parsesRealPdfText()throws Exception {
        byte[] bytes;
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()) {
            var page=new PDPage();doc.addPage(page);
            try(var stream=new PDPageContentStream(doc,page)) {
                stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                stream.newLineAtOffset(40,700);stream.showText("Travel policy hotel limit 600 CNY");stream.endText();
            }
            doc.save(out);bytes=out.toByteArray();
        }
        assertThat(parser.parse(file("travel.pdf",bytes)).text()).contains("hotel limit 600 CNY");
        var fixture=java.nio.file.Path.of("target","test-fixtures");java.nio.file.Files.createDirectories(fixture);
        java.nio.file.Files.write(fixture.resolve("travel.pdf"),bytes);
    }
    @Test void rejectsUnsupportedOrDisguisedFiles() {
        assertThatThrownBy(()->parser.parse(file("bad.exe",new byte[]{1}))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->parser.parse(file("bad.pdf","plain text".getBytes(StandardCharsets.UTF_8)))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->parser.parse(file("bad.docx",new byte[]{1,2,3}))).isInstanceOf(BadRequestException.class);
    }
    @Test void rejectsEmptyOrOverlongText() {
        assertThatThrownBy(()->parser.parse(file("empty.txt",new byte[0]))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->parser.parse(file("long.txt","a".repeat(200001).getBytes(StandardCharsets.UTF_8)))).isInstanceOf(BadRequestException.class);
    }
    @Test void splitsLongParagraphsIntoBoundedOverlappingChunks() {
        var service=new KnowledgeService(mock(com.agentops.persistence.KnowledgeRepo.class),mock(com.agentops.model.EmbeddingRouter.class));
        var chunks=service.chunk("# 标题\n"+"关键事实".repeat(800));
        assertThat(chunks.size()).isGreaterThan(3);
        assertThat(chunks).allSatisfy(chunk->assertThat(chunk.text().length()).isLessThanOrEqualTo(600));
        assertThat(chunks.get(1).heading()).isEqualTo("标题");
    }
}
