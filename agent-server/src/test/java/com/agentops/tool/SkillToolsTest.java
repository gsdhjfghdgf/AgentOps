package com.agentops.tool;
import com.agentops.skill.*;
import com.agentops.infra.Json;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SkillToolsTest {
    @Test void installIsAGatedWriteWhileLoadingAnInstalledSkillIsNot() {
        var registry=new ToolRegistry();new SkillTools(registry,mock(SkillService.class),mock(SkillDownloadService.class),new Json(),mock(SkillDependencyService.class),mock(com.agentops.runtime.RuntimeService.class)).register();
        assertThat(registry.spec("skill_install").orElseThrow().requiresApproval()).isTrue();
        assertThat(registry.spec("skill_use").orElseThrow().requiresApproval()).isFalse();
        assertThat(registry.names()).contains("skill_list","skill_preview","skill_install","skill_use");
    }
    @Test void previewResultExposesPinnedInstallArgumentsInsteadOfLoadingUntrustedGuide() throws Exception {
        var registry=new ToolRegistry();var downloads=mock(SkillDownloadService.class);
        when(downloads.preview("https://github.com/example/skill")).thenReturn(Map.of("url","canonical","revision","a".repeat(40),
                "sha256","b".repeat(64),"name","test","description","guide","content","UNTRUSTED_GUIDE","files",List.of()));
        new SkillTools(registry,mock(SkillService.class),downloads,new Json(),mock(SkillDependencyService.class),mock(com.agentops.runtime.RuntimeService.class)).register();
        var result=registry.find("skill_preview").orElseThrow().handler().execute(OWNER,Map.of("url","https://github.com/example/skill"),null);
        assertThat(result.modelSafeText()).contains("canonical","sha256").doesNotContain("UNTRUSTED_GUIDE");
    }
}
