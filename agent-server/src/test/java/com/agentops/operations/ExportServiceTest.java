package com.agentops.operations;

import com.agentops.artifact.ArtifactService;
import com.agentops.domain.ResultTypes.ArtifactRef;
import com.agentops.infra.Json;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExportServiceTest {
    @TempDir Path temp;
    final ArtifactService artifacts=mock(ArtifactService.class);
    final OperationsService operations=mock(OperationsService.class);
    byte[] exported;
    ExportService service;
    @BeforeEach void setup(){
        service=new ExportService(artifacts,operations,new Json());
        when(operations.owned(OWNER,RUN)).thenReturn(rowWithConfig("{}"));
        when(artifacts.stagingPath(eq(OWNER.tenantId()),any(),anyString())).thenAnswer(i->temp.resolve("export."+i.getArgument(2)));
        when(artifacts.register(eq(OWNER),eq(SESSION),eq(RUN),anyString(),anyString(),any(),eq("output"))).thenAnswer(i->{exported=Files.readAllBytes(i.getArgument(5));return new ArtifactRef(UUID.randomUUID().toString(),i.getArgument(3),i.getArgument(4),exported.length,"sha");});
    }
    @Test void wordContainsRealChineseTextInAReadableOfficeFile()throws Exception{
        var result=service.export(OWNER,RUN,"docx","项目报告","本周完成任务。\n下一步完善功能。");
        assertThat(result.originalName()).endsWith(".docx");
        try(var doc=new XWPFDocument(new ByteArrayInputStream(exported))){assertThat(doc.getParagraphs()).extracting(p->p.getText()).contains("项目报告","本周完成任务。","下一步完善功能。");}
        assertThat(temp.resolve("export.docx")).doesNotExist();
    }
    @Test void excelPreservesTypesAndTreatsFormulaLikeInputAsText()throws Exception{
        service.export(OWNER,RUN,"xlsx","数据","[[\"名称\",\"数量\"],[\"=HYPERLINK(1)\",12]]");
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(exported))){var row=book.getSheetAt(0).getRow(1);assertThat(row.getCell(0).getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);assertThat(row.getCell(0).getStringCellValue()).isEqualTo("=HYPERLINK(1)");assertThat(row.getCell(1).getNumericCellValue()).isEqualTo(12);}
    }
    @Test void chinesePdfIsReadableAndContainsTheActualContent()throws Exception{
        service.export(OWNER,RUN,"pdf","测试报告","这是中文导出验证。\n完成率百分之百。");
        try(var pdf=Loader.loadPDF(exported)){assertThat(new PDFTextStripper().getText(pdf)).contains("测试报告","这是中文导出验证","完成率百分之百");}
    }
    @Test void invalidSpreadsheetDoesNotRegisterAnArtifact()throws Exception{
        assertThatThrownBy(()->service.export(OWNER,RUN,"xlsx","数据","[1,2]")).isInstanceOf(IllegalArgumentException.class);
        verify(artifacts,never()).register(any(),any(),any(),any(),any(),any(),any());
        assertThat(temp.resolve("export.xlsx")).doesNotExist();
    }
}
