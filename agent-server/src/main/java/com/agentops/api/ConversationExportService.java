package com.agentops.api;

import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.AgentDomain.MessageRole;
import com.agentops.infra.Json;
import com.agentops.persistence.MessageRepo;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Export a complete owned conversation, never raw tool arguments, model secrets or silent truncation. */
@Service
public class ConversationExportService {
    private final RunApplicationService app;private final MessageRepo messages;private final Json json;
    public ConversationExportService(RunApplicationService app,MessageRepo messages,Json json){this.app=app;this.messages=messages;this.json=json;}
    public record Export(String filename,String markdown){}
    public Export export(Principal owner,java.util.UUID session){
        var view=app.getSession(owner,session);
        String title=view.title()==null?"会话":view.title().replaceAll("[\\p{Cntrl}]"," ");
        var output=new StringBuilder("# "+title+"\n\n");long cursor=0;int count=0;
        while(true){
            var rows=messages.listAfter(owner,session,cursor,200);
            if(rows.isEmpty())break;
            for(var row:rows){
                if(++count>10000)throw tooLarge();cursor=row.seq();
                if(row.role()!=MessageRole.USER&&row.role()!=MessageRole.ASSISTANT)continue;
                var payload=json.tree(row.payloadJson());String kind=payload.path("kind").asText(),content=payload.path("content").asText("");
                if(content.isBlank()||row.role()==MessageRole.ASSISTANT&&(kind.equals("commentary")||kind.equals("milestone")||payload.path("toolCalls").isArray()&&!payload.path("toolCalls").isEmpty()))continue;
                output.append("## ").append(row.role()==MessageRole.USER?"你":"AgentOps").append("\n\n").append(content).append("\n\n");
                if(output.length()>2_000_000)throw tooLarge();
            }
        }
        String safe=title.replaceAll("[\\\\/:*?\"<>|]","_").strip();if(safe.isBlank())safe="会话";
        return new Export(safe.substring(0,Math.min(safe.length(),100))+".md",output.toString());
    }
    private static ResponseStatusException tooLarge(){return new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"会话过大，暂不支持一次导出，请按任务拆分；不会输出不完整文件");}
}
