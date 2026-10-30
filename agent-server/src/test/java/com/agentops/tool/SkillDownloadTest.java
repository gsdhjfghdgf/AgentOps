package com.agentops.tool;
import com.agentops.skill.*;
import com.agentops.infra.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SkillDownloadTest {
    @TempDir Path directory;
    final String revision="a".repeat(40),url="https://github.com/example/repo/tree/main/skills/demo";
    final String main="---\nname: demo-skill\ndescription: fixture\nkeywords: demo\n---\nRead scripts/demo.py.";
    final String script="raise RuntimeError('THIS_SCRIPT_MUST_NOT_EXECUTE')";
    final Json json=new Json();
    SkillService skills;WorkspaceService workspace;SkillDownloadService service;PublicWebClient web;
    @BeforeEach void setup() throws Exception {
        web=mock(PublicWebClient.class);skills=mock(SkillService.class);workspace=new WorkspaceService(directory.toString());
        when(skills.save(eq(OWNER),any())).thenReturn(UUID.randomUUID());
        when(web.get(anyString(),anyInt())).thenAnswer(invocation->{
            String address=invocation.getArgument(0),path=java.net.URI.create(address).getPath(),body;
            if(address.contains("/git/trees/"))body=json.write(Map.of("truncated",false,"tree",List.of(
                    Map.of("path","skills/demo/SKILL.md","mode","100644","type","blob","sha","main","size",main.length()),
                    Map.of("path","skills/demo/scripts/demo.py","mode","100644","type","blob","sha","script","size",script.length()))));
            else if(address.contains("raw.githubusercontent.com"))body=path.endsWith("SKILL.md")?main:script;
            else if(address.contains("/commits?"))body=json.write(List.of(Map.of("sha",revision)));
            else if(path.endsWith("/SKILL.md"))body=file(main);
            else if(path.endsWith("/scripts/demo.py"))body=file(script);
            else if(path.endsWith("/scripts"))body=json.write(List.of(Map.of("name","demo.py","path","skills/demo/scripts/demo.py","sha","script","size",script.length(),"type","file")));
            else body=json.write(List.of(Map.of("name","SKILL.md","path","skills/demo/SKILL.md","sha","main","size",main.length(),"type","file"),
                    Map.of("name","scripts","path","skills/demo/scripts","type","dir")));
            return new PublicWebClient.Page(address,body,"application/json");
        });
        service=new SkillDownloadService(web,json,workspace,skills);
    }
    String file(String content){return json.write(Map.of("type","file","encoding","base64","size",content.length(),"content",Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8))));}
    @Test void previewPinsRevisionAndInstallationDownloadsWithoutExecution() throws Exception {
        var preview=service.preview(url);assertThat(preview).containsEntry("revision",revision).containsEntry("name","demo-skill");
        var installed=service.install(OWNER,new SkillDownloadService.ImportRequest(url,revision,preview.get("sha256").toString()));
        String folder=installed.get("directory").toString();
        assertThat(workspace.read(OWNER,folder+"/scripts/demo.py")).containsEntry("content",script);
        verify(skills).enabled(eq(OWNER),startsWith("user:"),eq(true));
    }
    @Test void unreviewedChangedContentCannotBeInstalled() throws Exception {
        assertThatThrownBy(()->service.install(OWNER,new SkillDownloadService.ImportRequest(url,revision,"wrong"))).hasMessageContaining("重新预览");
        assertThat(workspace.list(OWNER)).isEmpty();verify(skills,never()).save(any(),any());
    }
    @Test void repositoryRootFindsNestedSkillAndSameVersionInstallDoesNotDuplicateFiles() throws Exception {
        var preview=service.preview("https://github.com/example/repo/tree/main");
        assertThat(preview.get("url").toString()).endsWith("/skills/demo");
        var request=new SkillDownloadService.ImportRequest(preview.get("url").toString(),revision,preview.get("sha256").toString());
        var first=service.install(OWNER,request);var second=service.install(OWNER,request);
        assertThat(first.get("directory")).isEqualTo(second.get("directory"));
        assertThat(workspace.list(OWNER)).hasSize(2);
    }
    @Test void longGuideAndLargeMjsResourcesAreInstalledWithoutTruncation() throws Exception {
        String guide=main+"\n"+"Guide ".repeat(6000),module="// module\n"+"x".repeat(600000);
        when(web.get(contains("/git/trees/"),anyInt())).thenReturn(new PublicWebClient.Page("tree",json.write(Map.of("tree",List.of(
                Map.of("path","skills/demo/SKILL.md","mode","100644","type","blob","sha","main","size",guide.length()),
                Map.of("path","skills/demo/scripts/run.mjs","mode","100644","type","blob","sha","module","size",module.length())))),"application/json"));
        when(web.get(contains("raw.githubusercontent.com"),anyInt())).thenAnswer(i->new PublicWebClient.Page(i.getArgument(0),
                i.<String>getArgument(0).endsWith("SKILL.md")?guide:module,"text/plain"));
        var preview=service.preview(url);
        var installed=service.install(OWNER,new SkillDownloadService.ImportRequest(preview.get("url").toString(),revision,preview.get("sha256").toString()));
        assertThat(workspace.read(OWNER,installed.get("directory")+"/scripts/run.mjs").get("content")).isEqualTo(module);
        var capture=org.mockito.ArgumentCaptor.forClass(SkillService.SkillInput.class);verify(skills).save(eq(OWNER),capture.capture());
        assertThat(capture.getValue().content()).contains(guide);
        assertThat(installed).containsEntry("registered",true).containsEntry("scriptsExecuted",false);
    }
    @Test void multipleSkillsRequireSelectionAndDoNotInstallAnything() throws Exception {
        when(web.get(contains("/git/trees/"),anyInt())).thenReturn(new PublicWebClient.Page("tree",json.write(Map.of("tree",List.of(
                Map.of("path","skills/one/SKILL.md","mode","100644","type","blob"),
                Map.of("path","skills/two/SKILL.md","mode","100644","type","blob")))),"application/json"));
        var result=service.preview("https://github.com/example/repo/tree/main");
        assertThat(result).containsEntry("selectionRequired",true);
        assertThat((List<?>)result.get("candidates")).hasSize(2);
        verify(skills,never()).save(any(),any());assertThat(workspace.list(OWNER)).isEmpty();
    }
}
