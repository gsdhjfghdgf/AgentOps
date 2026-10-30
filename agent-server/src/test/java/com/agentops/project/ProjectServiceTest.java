package com.agentops.project;
import com.agentops.persistence.RunRepo;
import com.agentops.tool.WorkspaceService;
import com.agentops.infra.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ProjectServiceTest {
    @TempDir Path directory;final ProjectRepo repo=mock(ProjectRepo.class);final RunRepo runs=mock(RunRepo.class);ProjectService service;ProjectRepo.Project project;UUID projectId=UUID.randomUUID();
    @BeforeEach void setup() throws Exception {project=new ProjectRepo.Project(projectId,"project",directory.toRealPath().toString(),"");service=new ProjectService(repo,runs,new Json());when(repo.get(OWNER,projectId)).thenReturn(project);when(runs.findByIdOwner(OWNER,RUN)).thenReturn(Optional.of(rowWithConfig("{\"projectId\":\""+projectId+"\"}")));}
    @Test void projectReadsStayPinnedAndRejectTraversal() throws Exception {
        Files.writeString(directory.resolve("hello.txt"),"project content");assertThat(service.read(OWNER,RUN,"hello.txt",0)).containsEntry("content","project content");
        for(String path:List.of("../outside.txt","C:/Windows/win.ini",".git/config","folder\\file.txt"))assertThatThrownBy(()->service.resolve(project,path)).isInstanceOf(IllegalArgumentException.class);
        verify(repo).get(OWNER,projectId);
    }
    @Test void writesRequireFreshHashAndUndoRestoresExactOriginal() throws Exception {
        String before="first\r\nsecond\r\n";Path file=directory.resolve("note.txt");Files.writeString(file,before);
        assertThatThrownBy(()->service.write(OWNER,RUN,"note.txt","new","stale")).hasMessageContaining("变化");assertThat(Files.readString(file)).isEqualTo(before);
        String sha=WorkspaceService.sha(before.getBytes(StandardCharsets.UTF_8));var result=service.write(OWNER,RUN,"note.txt","new",sha);UUID id=UUID.fromString(result.get("changeId").toString());
        when(repo.changes(OWNER,projectId)).thenReturn(List.of(new ProjectRepo.Change(id,projectId,RUN,"note.txt",before,sha,result.get("sha256").toString(),"applied","now")));
        service.undo(OWNER,projectId,id);assertThat(Files.readString(file)).isEqualTo(before);
    }
    @Test void undoNeverOverwritesSubsequentUserChanges() throws Exception {
        UUID id=UUID.randomUUID();Files.writeString(directory.resolve("note.txt"),"user edit");when(repo.changes(OWNER,projectId)).thenReturn(List.of(new ProjectRepo.Change(id,projectId,RUN,"note.txt","old","before","expected","applied","now")));
        assertThatThrownBy(()->service.undo(OWNER,projectId,id)).hasMessageContaining("后续修改");assertThat(Files.readString(directory.resolve("note.txt"))).isEqualTo("user edit");
    }
    @Test void patchMustMatchOneUniqueFragment() throws Exception {
        Files.writeString(directory.resolve("code.txt"),"unique = 1\nsame\nsame\n");assertThat(service.patchedContent(project,"code.txt","unique = 1","unique = 2")).startsWith("unique = 2");
        assertThatThrownBy(()->service.patchedContent(project,"code.txt","same","other")).hasMessageContaining("匹配多处");
    }
    @Test void nestedFilesCanBeCreatedAndPatchedButAnUnselectedRunCannotWrite() throws Exception {
        var created=service.write(OWNER,RUN,"src/nested/hello.txt","hello","");
        assertThat(service.read(OWNER,RUN,"src/nested/hello.txt",0)).containsEntry("content","hello");
        service.patch(OWNER,RUN,"src/nested/hello.txt","hello","updated",created.get("sha256").toString());
        assertThat(Files.readString(directory.resolve("src/nested/hello.txt"))).isEqualTo("updated");
        when(runs.findByIdOwner(OWNER,RUN)).thenReturn(Optional.of(rowWithConfig("{}")));
        assertThatThrownBy(()->service.write(OWNER,RUN,"unauthorized.txt","blocked","")).hasMessageContaining("选择项目");
        assertThat(directory.resolve("unauthorized.txt")).doesNotExist();
    }
    @Test void largerPagesExposeLineNumbersAndAnExplicitEndOfFile()throws Exception{
        String content="header\n"+"a".repeat(8000)+"\nend";Files.writeString(directory.resolve("large.txt"),content);
        var first=service.read(OWNER,RUN,"large.txt",0);assertThat(first).containsEntry("nextOffset",6000).containsEntry("eof",false).containsEntry("fromLine",1).containsEntry("toLine",2);
        var last=service.read(OWNER,RUN,"large.txt",6000);assertThat(last).containsEntry("eof",true).containsEntry("nextOffset",content.length()).containsEntry("fromLine",2);
        assertThat(first.get("content").toString()+last.get("content").toString()).isEqualTo(content);
    }
}
