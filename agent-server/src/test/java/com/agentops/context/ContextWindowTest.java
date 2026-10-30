package com.agentops.context;

import com.agentops.infra.Json;
import com.agentops.model.ModelPort.ModelMessage;
import com.agentops.model.ModelPort.ToolCall;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ContextWindowTest {
    final Json json=new Json();
    final ContextWindow window=new ContextWindow(json);
    @Test void reusedProviderCallIdsCannotMakeAnOldMutationLookLikeARead() {
        var mutation=ModelMessage.assistantWithCalls("",List.of(new ToolCall("same","mcp_call","{}")));
        var unknown=ModelMessage.toolResult("same",json.write(Map.of("status","unknown","content","需要人工核对".repeat(400))));
        var rows=new ArrayList<ModelMessage>(List.of(ModelMessage.user("检查"),mutation,unknown));
        for(int i=0;i<12;i++)rows.add(ModelMessage.assistant("阶段"+i));
        rows.add(ModelMessage.assistantWithCalls("",List.of(new ToolCall("same","project_read","{}"))));
        rows.add(ModelMessage.toolResult("same","{\"content\":\"read\"}"));
        assertThat(window.fit(rows,10000,"检查")).contains(unknown);
    }

    @Test void oversizedReadIsReducedWithItsMetadataAndMatchingCallPreserved() {
        var request=ModelMessage.user("检查当前文件");
        var call=ModelMessage.assistantWithCalls("",List.of(new ToolCall("r","project_read","{\"path\":\"app.js\"}")));
        var result=ModelMessage.toolResult("r",json.write(Map.of("path","app.js","sha256","abc","eof",true,
                "content","证据".repeat(10000),"status","ok")));
        var kept=window.fit(List.of(request,call,result),700,request.content());
        assertThat(kept).hasSize(3).contains(request,call);
        var evidence=json.tree(kept.get(2).content());
        assertThat(evidence.path("sha256").asText()).isEqualTo("abc");
        assertThat(evidence.path("status").asText()).isEqualTo("ok");
        assertThat(evidence.path("contextNotice").asText()).contains("摘录");
        assertThat(evidence.path("content").asText().length()).isLessThan(2000);
    }
    @Test void latestRequirementsStayExactAndReadBlocksAreNeverSplit() {
        var input=new ArrayList<ModelMessage>();
        for(int i=0;i<40;i++)input.add(ModelMessage.assistant("旧说明"+i+"x".repeat(500)));
        String goal="原始目标不可删改";
        input.add(ModelMessage.user(goal));
        input.add(ModelMessage.assistantWithCalls("",List.of(new ToolCall("r","project_read","{}"))));
        input.add(ModelMessage.toolResult("r","{\"content\":\"evidence\",\"sha256\":\"a\"}"));
        input.add(ModelMessage.user("最新补充也要保留"));
        var kept=window.fit(input,350,goal);
        assertThat(kept).contains(ModelMessage.user(goal),ModelMessage.user("最新补充也要保留"));
        assertThat(kept.size()).isLessThan(input.size());
        assertThat(kept.stream().filter(m->"tool".equals(m.role()))).allMatch(m->kept.stream()
                .flatMap(a->a.toolCalls().stream()).anyMatch(c->c.id().equals(m.toolCallId())));
    }
    @Test void mutationUnknownOutcomeIsNotSummarizedAsARead() {
        var call=ModelMessage.assistantWithCalls("",List.of(new ToolCall("m","mcp_call","{}")));
        var result=ModelMessage.toolResult("m",json.write(Map.of("status","unknown","content","需要核对".repeat(1000))));
        var kept=window.fit(List.of(ModelMessage.user("核对结果"),call,result),10000,"核对结果");
        assertThat(kept).contains(result);
    }
}
