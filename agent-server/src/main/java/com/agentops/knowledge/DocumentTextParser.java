package com.agentops.knowledge;

import com.agentops.api.RunApplicationService.BadRequestException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.OfficeParser;
import org.apache.tika.parser.microsoft.ooxml.OOXMLParser;
import org.apache.tika.parser.pdf.PDFParser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.ContentHandler;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipInputStream;

@Component
public class DocumentTextParser {
    public static final int MAX_CHARS=200_000;
    private static final long MAX_BYTES=20L*1024*1024;
    public record Parsed(String filename,String text) {}

    public Parsed parse(MultipartFile file) {
        String name=file.getOriginalFilename()==null?"document":file.getOriginalFilename();
        name=name.replace('\\','/'); name=name.substring(name.lastIndexOf('/')+1);
        int dot=name.lastIndexOf('.');
        String extension=dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
        if(!Set.of("pdf","docx","doc","txt","md","csv").contains(extension))
            throw new BadRequestException("支持 PDF、DOCX、DOC、TXT、Markdown 和 CSV 文件");
        if(file.isEmpty() || file.getSize()>MAX_BYTES) throw new BadRequestException("文件不能为空，且不能超过 20 MB");
        try(var input=file.getInputStream()) {
            byte[] bytes=input.readNBytes((int)MAX_BYTES+1);
            if(bytes.length>MAX_BYTES)throw new BadRequestException("文件不能超过 20 MB");
            String text;
            if(Set.of("txt","md","csv").contains(extension)) {
                text=decode(bytes);
                if(text.indexOf('\0')>=0)throw new BadRequestException("文件不是可读取的文本");
            } else {
                Parser parser;
                if(extension.equals("pdf")) {
                    if(bytes.length<5 || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))
                        throw new BadRequestException("PDF 文件内容与扩展名不一致");
                    parser=new PDFParser();
                } else if(extension.equals("docx")) { checkDocx(bytes); parser=new OOXMLParser(); }
                else {
                    if(bytes.length<8 || bytes[0]!=(byte)0xd0 || bytes[1]!=(byte)0xcf)
                        throw new BadRequestException("DOC 文件内容与扩展名不一致");
                    parser=new OfficeParser();
                }
                var context=new ParseContext();
                var config=new PDFParserConfig();
                config.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
                config.setExtractActions(false); config.setExtractInlineImages(false);
                context.set(PDFParserConfig.class,config);
                context.set(EmbeddedDocumentExtractor.class,new EmbeddedDocumentExtractor() {
                    public boolean shouldParseEmbedded(Metadata metadata){return false;}
                    public void parseEmbedded(InputStream stream,ContentHandler handler,Metadata metadata,boolean outputHtml){}
                });
                var handler=new BodyContentHandler(MAX_CHARS);
                try(var stream=new ByteArrayInputStream(bytes)) {parser.parse(stream,handler,new Metadata(),context);}
                text=handler.toString();
            }
            text=text.replace("\uFEFF","").strip();
            if(text.isBlank()) throw new BadRequestException("未提取到文字；扫描版 PDF 请先进行 OCR，或上传带文本层的文档");
            if(text.length()>MAX_CHARS)throw new BadRequestException("文档文字超过 20 万字，请拆分后导入");
            return new Parsed(name,text);
        } catch(BadRequestException ex){throw ex;}
        catch(Exception ex){throw new BadRequestException("无法解析文档，请检查文件是否损坏、加密或超过文字限制");}
    }

    private String decode(byte[] bytes)throws CharacterCodingException {
        if(bytes.length>=2 && ((bytes[0]==(byte)0xff && bytes[1]==(byte)0xfe)||(bytes[0]==(byte)0xfe&&bytes[1]==(byte)0xff)))
            return StandardCharsets.UTF_16.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        try{return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException ex){return Charset.forName("GB18030").newDecoder().decode(ByteBuffer.wrap(bytes)).toString();}
    }
    private void checkDocx(byte[] bytes)throws IOException {
        boolean word=false; int entries=0; long size=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry; var buffer=new byte[8192];
            while((entry=zip.getNextEntry())!=null) {
                if(++entries>2000)throw new BadRequestException("Word 文档内的文件数量过多");
                if(entry.getName().equals("word/document.xml"))word=true;
                int count; while((count=zip.read(buffer))!=-1) {
                    size+=count; if(size>50L*1024*1024)throw new BadRequestException("Word 文档解压后过大");
                }
            }
        }
        if(!word)throw new BadRequestException("文件不是有效的 DOCX 文档");
    }
}
