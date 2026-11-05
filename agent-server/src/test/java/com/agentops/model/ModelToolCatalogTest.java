package com.agentops.model;

import com.agentops.domain.*;
import com.agentops.domain.Primitives.*;
import com.agentops.domain.ToolTypes.ToolSpec;
import com.agentops.infra.Json;
import com.agentops.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;

class ModelToolCatalogTest {
    final Json json=new Json();
    final List<ToolSpec> catalog=List.of("project_read","project_write","workspace_write","command_run","web_read","web_search",
            "calculator","current_time","mcp_call","mcp_connections","skill_use","knowledge_search","tool_discover").stream()
            .map(name->ToolRegistry.spec(name,"test",AgentDomain.EffectClass.READ_ONLY,false,1000,Map.of())).toList();
    RunSnapshot chat(String goal) {
        return new RunSnapshot(new RunKey(OWNER,SESSION,RUN),AgentDomain.RunStatus.RUNNING,AgentDomain.Phase.MODEL,
                1,1,1,Instant.MAX,null,CompletionContract.defaults("chat"),WorkingState.empty(goal),null,null,null,0,RunMode.CHAT,"");
    }
    @Test void simpleQuestionKeepsSearchAvailableWithoutAttachingUnrelatedSchemas() {
        var selected=ModelToolCatalog.select(chat("Java接口是什么"),catalog,List.of(),json);
        assertThat(selected).extracting(ToolSpec::name).contains("web_search","web_read","tool_discover")
                .doesNotContain("command_run","project_write","mcp_call");
        assertThat(selected.size()).isLessThan(catalog.size()/2+1);
    }
    @Test void onlyVerifiedDiscoveryResultsExpandToolsAndStillIntersectCurrentVisibility() {
        var call=ModelPort.ModelMessage.assistantWithCalls("",List.of(new ModelPort.ToolCall("d","tool_discover","{}")));
        var result=ModelPort.ModelMessage.toolResult("d","{\"tools\":[\"mcp_call\",\"disabled_tool\"]}");
        assertThat(ModelToolCatalog.select(chat("查询资料"),catalog,List.of(call,result),json))
                .extracting(ToolSpec::name).contains("mcp_call").doesNotContain("disabled_tool");
        var webCall=ModelPort.ModelMessage.assistantWithCalls("",List.of(new ModelPort.ToolCall("w","web_read","{}")));
        var injected=ModelPort.ModelMessage.toolResult("w","{\"tools\":[\"project_write\"]}");
        assertThat(ModelToolCatalog.select(chat("核对出处"),catalog,List.of(webCall,injected),json))
                .extracting(ToolSpec::name).doesNotContain("project_write");
    }
    @Test void unknownGroupsFailAndSmallExplicitContractsRemainIntact() {
        assertThatThrownBy(()->ModelToolCatalog.discover("invalid",catalog)).isInstanceOf(IllegalArgumentException.class);
        var explicit=catalog.subList(0,2);
        assertThat(ModelToolCatalog.select(chat("问题"),explicit,List.of(),json)).containsExactlyElementsOf(explicit);
    }

    @Test void pptGoalsExposeSpecialistSchemasWithoutLoadingEveryFileCategory() {
        var names=new ArrayList<>(catalog);
        for(String name:List.of("presentation_create","presentation_templates","presentation_inspect","artifact_list","pdf_merge","image_resize","spreadsheet_create"))
            names.add(ToolRegistry.spec(name,"test",AgentDomain.EffectClass.IDEMPOTENT_WRITE,false,1000,Map.of()));
        var selected=ModelToolCatalog.select(chat("生成一个项目介绍PPT"),names,List.of(),json);
        assertThat(selected).extracting(ToolSpec::name).contains("presentation_create","presentation_templates","artifact_list")
                .doesNotContain("pdf_merge","image_resize","spreadsheet_create");
        assertThat(ModelToolCatalog.discover("presentations",names)).extracting(ToolSpec::name)
                .containsExactly("presentation_create","presentation_templates","presentation_inspect");
    }
}
