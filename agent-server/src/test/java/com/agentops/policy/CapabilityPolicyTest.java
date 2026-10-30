package com.agentops.policy;
import com.agentops.domain.AgentDomain.*;
import com.agentops.domain.Primitives.*;
import com.agentops.domain.ToolTypes.*;
import com.agentops.infra.Json;
import com.agentops.persistence.CapabilityRepo;
import com.agentops.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CapabilityPolicyTest {
    RunSnapshot mode(com.agentops.domain.RunMode mode) {
        var s = snapshot("查询资料");
        return new RunSnapshot(s.key(),s.status(),s.phase(),s.stateVersion(),s.checkpointVersion(),s.workerEpoch(),
                s.deadlineAt(),s.cancelRequestedAt(),s.contract(),s.workingState(),s.versions(),
                s.questionId(),s.questionText(),s.repairCount(),mode,"");
    }
    @Test void chatCanResearchAndNetworkConsentStillApplies() {
        tool("web_search",true);tool("web_read",true);tool("knowledge_search",false);tool("mcp_call",true);
        var chat=mode(com.agentops.domain.RunMode.CHAT);
        assertThat(policy.visibleTools(chat)).extracting(ToolSpec::name)
                .contains("web_search","web_read","knowledge_search","mcp_call");
        assertThat(policy.evaluate(OWNER,chat,intent("web_search")).verdict()).isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
        when(runs.hasNetworkPermission(OWNER,RUN)).thenReturn(true);
        assertThat(policy.evaluate(OWNER,chat,intent("web_read")).verdict()).isEqualTo(PolicyVerdict.ALLOW);
        assertThat(policy.evaluate(OWNER,chat,intent("mcp_call")).verdict()).isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
    }
    @Test void planCannotWriteOrExecuteEvenWithPreviouslyGrantedPermissions() {
        for(var name:List.of("project_patch","workspace_write","command_run","sandbox_run","skill_install","mcp_call","browser_click"))
            tool(name,true);
        var plan=mode(com.agentops.domain.RunMode.PLAN);
        assertThat(policy.visibleTools(plan)).isEmpty();
        for(var name:List.of("project_patch","workspace_write","command_run","sandbox_run","skill_install","mcp_call","browser_click"))
            assertThat(policy.evaluate(OWNER,plan,intent(name)).reasonCode()).isEqualTo("MODE_TOOL_DENIED:plan");
        verifyNoInteractions(runs);
    }
    @Test void planCanInspectAndSaveItsPlanButCannotExportOrPretendToExecute() {
        for(var name:List.of("project_read","project_git_diff","plan_update","task_notes","report_export"))
            tool(name,false);
        var plan=mode(com.agentops.domain.RunMode.PLAN);
        assertThat(policy.visibleTools(plan)).extracting(ToolSpec::name)
                .contains("project_read","project_git_diff","plan_update","task_notes").doesNotContain("report_export");
        assertThat(policy.evaluate(OWNER,plan,intent("project_read")).verdict()).isEqualTo(PolicyVerdict.ALLOW);
    }
    final ToolRegistry registry=new ToolRegistry();final CapabilityRepo repo=mock(CapabilityRepo.class);
    final com.agentops.persistence.RunRepo runs=mock(com.agentops.persistence.RunRepo.class);
    final PolicyService policy=new PolicyService(registry,new Json(),Clock.systemUTC(),repo,runs);
    RunSnapshot snapshot(){return snapshot("");}
    RunSnapshot snapshot(String goal){return new RunSnapshot(new RunKey(OWNER,SESSION,RUN),RunStatus.RUNNING,Phase.TOOLS,1,1,1,Instant.MAX,null,
            CompletionContract.defaults("test"),WorkingState.empty(goal),null,null,null,0);}
    void tool(String name,boolean approval){registry.register(ToolRegistry.spec(name,"test",approval?EffectClass.IDEMPOTENT_WRITE:EffectClass.READ_ONLY,approval,1000,Map.of()),(p,a,c)->ToolResult.success("ok"));}
    ToolIntent intent(String name){return new ToolIntent(UUID.randomUUID(),"c1",name,"1.0.0",Map.of(),"","","");}
    @Test void sandboxGrantNeverGrantsHostCommandPermission(){
        tool("sandbox_run",true);tool("command_run",true);
        when(repo.preferences(OWNER,"tool")).thenReturn(Map.of("command_run",new CapabilityRepo.Preference(true,true)));
        when(runs.hasSandboxPermission(OWNER,RUN)).thenReturn(true);
        assertThat(policy.evaluate(OWNER,snapshot(),intent("sandbox_run")).verdict()).isEqualTo(PolicyVerdict.ALLOW);
        assertThat(policy.evaluate(OWNER,snapshot(),intent("command_run")).verdict()).isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
        when(runs.hasSandboxPermission(OWNER,RUN)).thenReturn(false);
        assertThat(policy.evaluate(OWNER,snapshot(),intent("sandbox_run")).verdict()).isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
    }
    @Test void projectFileAccessUsesFolderConsentEvenWhenOldPreferencesAskedForApproval() {
        for(String name:List.of("project_list","project_read","project_search","project_write","project_patch","project_undo")) {
            tool(name,true);
            when(repo.preferences(OWNER,"tool")).thenReturn(Map.of(name,new CapabilityRepo.Preference(true,true)));
            assertThat(policy.evaluate(OWNER,snapshot(),intent(name)).verdict()).as(name).isEqualTo(PolicyVerdict.ALLOW);
        }
    }
    @Test void projectConsentDoesNotOverrideDisabledToolsOrGrantTerminalOrNetworkAccess() {
        tool("project_write",true);tool("command_run",true);tool("web_read",true);
        when(repo.preferences(OWNER,"tool")).thenReturn(Map.of("project_write",new CapabilityRepo.Preference(false,true),"command_run",new CapabilityRepo.Preference(true,true)));
        assertThat(policy.evaluate(OWNER,snapshot(),intent("project_write")).verdict()).isEqualTo(PolicyVerdict.DENY);
        assertThat(policy.evaluate(OWNER,snapshot(),intent("command_run")).verdict()).isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
        assertThat(policy.evaluate(OWNER,snapshot(),intent("web_read")).verdict()).isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
    }
    @Test void disabledToolsDisappearAndCannotBeExecuted() {
        tool("calculator",false);when(repo.preferences(OWNER,"tool")).thenReturn(Map.of("calculator",new CapabilityRepo.Preference(false,false)));
        assertThat(policy.visibleTools(snapshot())).isEmpty();
        assertThat(policy.evaluate(OWNER,snapshot(),intent("calculator")).verdict()).isEqualTo(PolicyVerdict.DENY);
    }
    @Test void readToolsMayRequireOneTimePermission() {
        tool("calculator",false);when(repo.preferences(OWNER,"tool")).thenReturn(Map.of("calculator",new CapabilityRepo.Preference(true,true)));
        assertThat(policy.evaluate(OWNER,snapshot(),intent("calculator")).verdict()).isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
    }
    @Test void preferencesCannotRemoveMandatoryApproval() {
        tool("workspace_write",true);when(repo.preferences(OWNER,"tool")).thenReturn(Map.of("workspace_write",new CapabilityRepo.Preference(true,false)));
        assertThat(policy.evaluate(OWNER,snapshot(),intent("workspace_write")).verdict()).isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
    }
    @Test void commandsRemainInvisibleUntilExplicitlyEnabled() {
        tool("command_run",true);when(repo.preferences(OWNER,"tool")).thenReturn(Map.of());
        assertThat(policy.visibleTools(snapshot())).isEmpty();
        when(repo.preferences(OWNER,"tool")).thenReturn(Map.of("command_run",new CapabilityRepo.Preference(true,true)));
        assertThat(policy.visibleTools(snapshot())).extracting(ToolSpec::name).containsExactly("command_run");
    }
    @Test void ordinaryPreferencesUseAutomaticSummarizationInsteadOfManualMemoryApprovals() {
        tool("memory_store",true);when(repo.preferences(OWNER,"tool")).thenReturn(Map.of());
        assertThat(policy.visibleTools(snapshot("以后请用中文回答。"))).isEmpty();
        assertThat(policy.visibleTools(snapshot("请手动保存这条记忆"))).extracting(ToolSpec::name).containsExactly("memory_store");
    }
    @Test void taskNetworkGrantCoversSearchAndReadButNoWritesOrOtherRuns() {
        tool("web_search",true);tool("web_read",true);tool("workspace_write",true);
        when(runs.hasNetworkPermission(OWNER,RUN)).thenReturn(true);
        assertThat(policy.evaluate(OWNER,snapshot(),intent("web_search")).verdict()).isEqualTo(PolicyVerdict.ALLOW);
        assertThat(policy.evaluate(OWNER,snapshot(),intent("web_read")).verdict()).isEqualTo(PolicyVerdict.ALLOW);
        assertThat(policy.evaluate(OWNER,snapshot(),intent("workspace_write")).verdict()).isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
        when(runs.hasNetworkPermission(OWNER,RUN)).thenReturn(false);
        assertThat(policy.evaluate(OWNER,snapshot(),intent("web_read")).verdict()).isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
    }
    @Test void commandGrantAppliesOnlyToEnabledCommandsInThatTask() {
        tool("command_run",true);tool("web_read",true);tool("workspace_write",true);
        when(repo.preferences(OWNER,"tool")).thenReturn(Map.of("command_run",new CapabilityRepo.Preference(true,true)));
        when(runs.hasCommandPermission(OWNER,RUN)).thenReturn(true);
        assertThat(policy.evaluate(OWNER,snapshot(),intent("command_run")).verdict()).isEqualTo(PolicyVerdict.ALLOW);
        assertThat(policy.evaluate(OWNER,snapshot(),intent("web_read")).verdict()).isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
        assertThat(policy.evaluate(OWNER,snapshot(),intent("workspace_write")).verdict()).isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
        when(runs.hasCommandPermission(OWNER,RUN)).thenReturn(false);
        assertThat(policy.evaluate(OWNER,snapshot(),intent("command_run")).verdict()).isEqualTo(PolicyVerdict.REQUIRE_APPROVAL);
        when(runs.hasCommandPermission(OWNER,RUN)).thenReturn(true);
        when(repo.preferences(OWNER,"tool")).thenReturn(Map.of("command_run",new CapabilityRepo.Preference(false,true)));
        assertThat(policy.evaluate(OWNER,snapshot(),intent("command_run")).verdict()).isEqualTo(PolicyVerdict.DENY);
    }
}
