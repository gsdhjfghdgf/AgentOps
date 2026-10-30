package com.agentops.api.web;
import com.agentops.knowledge.DocumentTextParser;
import com.agentops.knowledge.KnowledgeService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController
public class DocumentUploadController {
    private final DocumentTextParser parser;
    private final KnowledgeService knowledge;
    public DocumentUploadController(DocumentTextParser parser,KnowledgeService knowledge){this.parser=parser;this.knowledge=knowledge;}
    @PostMapping(value="/api/knowledge/documents/upload",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String,Object>> upload(@RequestHeader("X-Tenant-Id")String tenant,
            @RequestHeader("X-User-Id")String user,@RequestParam("file")MultipartFile file,
            @RequestParam(required=false)String title) {
        var owner=ApiController.principal(tenant,user);
        var parsed=parser.parse(file);
        var result=knowledge.importDocument(owner,title==null||title.isBlank()?parsed.filename():title.strip(),parsed.text(),"user");
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
