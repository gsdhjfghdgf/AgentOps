package com.agentops.api.web;

import com.agentops.api.ConversationExportService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
public class ConversationExportController {
    private final ConversationExportService service;
    public ConversationExportController(ConversationExportService service){this.service=service;}
    @GetMapping("/api/sessions/{id}/export")
    public ResponseEntity<byte[]> export(@RequestHeader("X-Tenant-Id")String tenant,@RequestHeader("X-User-Id")String user,@PathVariable UUID id){
        var data=service.export(ApiController.principal(tenant,user),id);
        return ResponseEntity.ok().contentType(new MediaType("text","markdown",StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(data.filename(),StandardCharsets.UTF_8).build().toString())
                .body(data.markdown().getBytes(StandardCharsets.UTF_8));
    }
}
