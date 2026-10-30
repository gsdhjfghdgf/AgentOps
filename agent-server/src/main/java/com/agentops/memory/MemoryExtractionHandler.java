package com.agentops.memory;

import com.agentops.domain.Primitives.Principal;
import com.agentops.infra.Json;
import com.agentops.outbox.OutboxDispatcher;
import com.agentops.persistence.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class MemoryExtractionHandler implements OutboxDispatcher.Handler {
    private final MemoryService memory;
    private final MessageRepo messages;
    private final SessionRepo sessions;
    private final RunRepo runs;
    private final Json json;
    public MemoryExtractionHandler(MemoryService memory,MessageRepo messages,SessionRepo sessions,RunRepo runs,Json json) {
        this.memory=memory;this.messages=messages;this.sessions=sessions;this.runs=runs;this.json=json;
    }
    public String type(){return "memory.extract";}
    public void handle(UUID tenantId,UUID userId,String eventKey,Map<String,Object> payload) {
        if(tenantId==null || userId==null)throw new IllegalArgumentException("缺少记忆任务身份");
        var owner=Principal.of(tenantId,userId);
        var runId=UUID.fromString(String.valueOf(payload.get("runId")));
        var run=runs.findByIdOwner(owner,runId).orElse(null);
        if(run==null)return;
        var session=sessions.findById(owner,run.sessionId()).orElse(null);
        if(session==null || !"chat".equals(session.kind()))return;
        var sources=new ArrayList<MemorySummarizer.Source>();
        int chars=0;
        for(var row:messages.listRunUsers(owner,runId)) {
            String content=json.tree(row.payloadJson()).path("content").asText("");
            if(content.isBlank())continue;
            if(content.length()>12000)continue;
            if(chars+content.length()>24000)break;
            sources.add(new MemorySummarizer.Source(row.seq(),content,row.createdAt()));
            chars+=content.length();
        }
        memory.extractFromRun(owner,run.sessionId(),sources);
    }
}
