package com.agentops.tool;

import com.agentops.TestRuns;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class ExtendedToolsTest {
    @TempDir Path directory;
    @Test void expiredTaskNeverStartsANewCommand() throws Exception {
        var registry=new ToolRegistry();var projects=org.mockito.Mockito.mock(com.agentops.project.ProjectService.class);
        new ExtendedTools(registry,new WorkspaceService(directory.toString()),
                org.mockito.Mockito.mock(com.agentops.knowledge.KnowledgeService.class),org.mockito.Mockito.mock(com.agentops.memory.MemoryService.class),
                org.mockito.Mockito.mock(com.agentops.artifact.ArtifactService.class),org.mockito.Mockito.mock(com.agentops.persistence.RunRepo.class),
                new com.agentops.infra.Json(),java.time.Clock.fixed(TestRuns.NOW,java.time.ZoneOffset.UTC),projects,org.mockito.Mockito.mock(com.agentops.runtime.RuntimeService.class),org.mockito.Mockito.mock(WebSearchService.class),org.mockito.Mockito.mock(WebPageReader.class)).register();
        var control=new com.agentops.model.ModelPort.ExecutionControl(){public boolean cancellationRequested(){return false;}public java.time.Instant deadlineAt(){return TestRuns.NOW.minusSeconds(1);}};
        var result=registry.find("command_run").orElseThrow().handler().execute(TestRuns.OWNER,java.util.Map.of("command","THIS_COMMAND_MUST_NOT_START","timeoutSeconds","300"),new ToolRegistry.ToolContext(TestRuns.RUN,TestRuns.QUESTION,"test",control));
        assertThat(result.errorCode()).isEqualTo("COMMAND_STOPPED");assertThat(result.modelSafeText()).contains("未启动");
    }
    @Test void calculatorUsesDecimalArithmeticWithoutEvaluatingCode() {
        assertThat(SafeCalculator.evaluate("(1200+300)*0.8")).isEqualTo("1200");
        assertThat(SafeCalculator.evaluate("0.1+0.2")).isEqualTo("0.3");
        assertThatThrownBy(()->SafeCalculator.evaluate("1/0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SafeCalculator.evaluate("Runtime.exec('x')")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SafeCalculator.evaluate("(".repeat(50)+"1"+")".repeat(50))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void csvRespectsQuotedCellsAndReportsInvalidValues() {
        var result=CsvStats.analyze("名称,金额\r\n\"A, B\",\"1,200\"\r\nC,300\r\nD,\r\nE,无效\r\n","金额");
        assertThat(result).containsEntry("sum","1500").containsEntry("average","750").containsEntry("valid",2).containsEntry("missing",1).containsEntry("invalid",1);
        assertThatThrownBy(()->CsvStats.analyze("a,b\n1,\"x","b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->CsvStats.analyze("a\n1e99999999","a")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void workspaceRejectsTraversalAndForeignOwners() throws Exception {
        var workspace=new WorkspaceService(directory.toString());var owner=TestRuns.OWNER;
        for(String path:java.util.List.of("../secret.txt","/etc/passwd","C:/secret.txt","folder\\secret.txt"))
            assertThatThrownBy(()->workspace.resolve(owner,path)).isInstanceOf(IllegalArgumentException.class);
        workspace.write(owner,"src/demo.py","print('hello')",null);
        assertThat(workspace.read(owner,"src/demo.py")).containsEntry("content","print('hello')");
        var foreign=new com.agentops.domain.Primitives.Principal(owner.tenantId(),java.util.UUID.randomUUID());
        assertThat(workspace.list(foreign)).isEmpty();
    }
    @Test void workspaceUpdatesRequireCurrentHash() throws Exception {
        var workspace=new WorkspaceService(directory.toString());var owner=TestRuns.OWNER;
        var first=workspace.write(owner,"notes.md","first",null);
        assertThatThrownBy(()->workspace.write(owner,"notes.md","overwritten",null)).hasMessageContaining("SHA256");
        workspace.write(owner,"notes.md","second",first.get("sha256").toString());
        assertThatThrownBy(()->workspace.write(owner,"notes.md","stale",first.get("sha256").toString())).hasMessageContaining("SHA256");
        assertThat(workspace.read(owner,"notes.md")).containsEntry("content","second");
    }
    @Test void publicWebBlocksPrivateAndMetadataEndpoints() {
        for(String url:java.util.List.of("http://127.0.0.1/","http://10.0.0.1/","http://169.254.169.254/","file:///etc/passwd","http://[::1]/"))
            assertThatThrownBy(()->PublicWebClient.validate(url)).isInstanceOf(IllegalArgumentException.class);
    }
}
