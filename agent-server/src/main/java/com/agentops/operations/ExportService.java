package com.agentops.operations;

import com.agentops.artifact.ArtifactService;
import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.ResultTypes.ArtifactRef;
import com.agentops.infra.Json;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.util.*;

@Service
public class ExportService {
    private final ArtifactService artifacts;private final OperationsService operations;private final Json json;
    public ExportService(ArtifactService artifacts,OperationsService operations,Json json){this.artifacts=artifacts;this.operations=operations;this.json=json;}
    public ArtifactRef export(Principal p,UUID run,String format,String title,String content)throws Exception{
        var task=operations.owned(p,run);if(format==null||!Set.of("docx","pdf","xlsx","md","html").contains(format)||title==null||title.isBlank()||title.length()>100||content==null||content.isBlank()||content.length()>100000)throw new IllegalArgumentException("格式、标题或正文无效");
        Path file=artifacts.stagingPath(p.tenantId(),UUID.randomUUID(),format);String name=title.replaceAll("[\\\\/:*?\"<>|\\r\\n]","_")+"."+format;
        try{
            switch(format){
                case "docx" -> {try(var doc=new XWPFDocument();var out=Files.newOutputStream(file)){
                    var heading=doc.createParagraph();heading.setAlignment(ParagraphAlignment.CENTER);var h=heading.createRun();h.setText(title);h.setBold(true);h.setFontSize(20);h.setFontFamily("Microsoft YaHei");
                    for(String line:content.split("\\R",-1)){var para=doc.createParagraph();para.setSpacingAfter(120);var r=para.createRun();r.setFontFamily("Microsoft YaHei");r.setFontSize(11);r.setText(line);}doc.write(out);
                }}
                case "xlsx" -> {var rows=json.tree(content);if(!rows.isArray()||rows.size()>2000)throw new IllegalArgumentException("Excel正文须为最多2000行的二维JSON数组");try(var book=new XSSFWorkbook();var out=Files.newOutputStream(file)){var sheet=book.createSheet("数据");int index=0;for(var row:rows){if(!row.isArray()||row.size()>50)throw new IllegalArgumentException("Excel每行须为数组且不超过50列");var r=sheet.createRow(index++);int col=0;for(var value:row){var cell=r.createCell(col++);if(value.isNumber())cell.setCellValue(value.asDouble());else if(value.isBoolean())cell.setCellValue(value.asBoolean());else cell.setCellValue(value.isNull()?"":value.asText().substring(0,Math.min(32000,value.asText().length())));}}sheet.createFreezePane(0,1);for(int i=0;i<50;i++)sheet.setColumnWidth(i,22*256);book.write(out);}}
                case "pdf" -> pdf(file,title+"\n\n"+content);
                default -> Files.writeString(file,content);
            }
            String mime=switch(format){case "docx"->"application/vnd.openxmlformats-officedocument.wordprocessingml.document";case "xlsx"->"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";case "pdf"->"application/pdf";case "html"->"text/html;charset=utf-8";default->"text/markdown;charset=utf-8";};
            return artifacts.register(p,task.sessionId(),run,name,mime,file,"output");
        }finally{Files.deleteIfExists(file);}
    }
    private void pdf(Path file,String text)throws Exception{
        Path fontPath=Path.of(System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"C:/Windows/Fonts/simsun.ttc":"/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc");
        if(!Files.isRegularFile(fontPath))throw new IllegalStateException("PDF中文字体缺失，请安装宋体或WenQuanYi字体");
        try(var doc=new PDDocument();var collection=new TrueTypeCollection(fontPath.toFile())){
            final org.apache.fontbox.ttf.TrueTypeFont[] selected={null};collection.processAllFonts(f->{if(selected[0]==null)selected[0]=f;});var font=PDType0Font.load(doc,selected[0],true);
            var lines=new ArrayList<String>();for(String paragraph:text.split("\\R",-1)){var line=new StringBuilder();float width=0;for(int cp:paragraph.codePoints().toArray()){
                String ch=new String(Character.toChars(cp));float size;try{size=font.getStringWidth(ch)/1000*11;}catch(IllegalArgumentException e){ch="?";size=font.getStringWidth(ch)/1000*11;}
                if(width+size>475&&!line.isEmpty()){lines.add(line.toString());line.setLength(0);width=0;}line.append(ch);width+=size;
            }lines.add(line.toString());}
            for(int start=0;start<lines.size();start+=42){var page=new PDPage();doc.addPage(page);try(var stream=new PDPageContentStream(doc,page)){stream.beginText();stream.setFont(font,11);stream.setLeading(17);stream.newLineAtOffset(58,745);for(int i=start;i<Math.min(start+42,lines.size());i++){stream.showText(lines.get(i));stream.newLine();}stream.endText();}}
            doc.save(file.toFile());
        }
    }
    public Map<String,Object> preview(Principal p,UUID id)throws Exception{
        var item=artifacts.resolveForDownload(p,id).orElseThrow(()->new com.agentops.api.RunApplicationService.NotFoundException("产物不存在"));String name=item.originalName().toLowerCase(Locale.ROOT);
        if(name.endsWith(".pptx")){
            if(item.byteSize()>com.agentops.filetools.FileToolSupport.MAX_BYTES)throw new IllegalArgumentException("PPT预览限20MB，较大文件请下载");
            try(var deck=new org.apache.poi.xslf.usermodel.XMLSlideShow(com.agentops.filetools.FileToolSupport.office(item.file()))){
                if(deck.getSlides().isEmpty()||deck.getSlides().size()>100)throw new IllegalArgumentException("PPT预览支持1～100页");
                return Map.of("type","presentation","slideCount",deck.getSlides().size(),"notice","使用POI渲染，与Office显示可能略有差异。");
            }
        }
        if(item.byteSize()>5_000_000)throw new IllegalArgumentException("预览限5MB，较大文件请下载");
        if(name.endsWith(".xlsx")){try(var in=Files.newInputStream(item.file());var book=new XSSFWorkbook(in)){var rows=new ArrayList<List<String>>();var sheet=book.getSheetAt(0);var format=new org.apache.poi.ss.usermodel.DataFormatter();for(var row:sheet){var cells=new ArrayList<String>();for(int c=0;c<Math.min(30,row.getLastCellNum());c++)cells.add(format.formatCellValue(row.getCell(c)));rows.add(cells);if(rows.size()>=100)break;}return Map.of("type","table","rows",rows,"limited",true);}}
        if(name.endsWith(".docx")){try(var in=Files.newInputStream(item.file());var doc=new XWPFDocument(in)){return Map.of("type","text","content",String.join("\n",doc.getParagraphs().stream().limit(1000).map(XWPFParagraph::getText).toList()));}}
        if(name.matches(".*\\.(md|txt|csv|html|json)$")){String content=Files.readString(item.file());return Map.of("type",name.endsWith(".html")?"html":"text","content",content.substring(0,Math.min(100000,content.length())));}
        return Map.of("type",name.endsWith(".pdf")?"pdf":name.matches(".*\\.(png|jpg|jpeg|webp)$")?"image":"download");
    }
}
