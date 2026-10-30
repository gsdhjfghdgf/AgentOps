package com.agentops.skill;
import com.agentops.runtime.RuntimeService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SkillDependencyTest {
    @Test void explicitRuntimeRequirementsAreDetectedWithoutExecutingSkillCode(){assertThat(SkillDependencyService.requirements("Requires Node 22+ and Chrome. Python >=3.10 optional tools.")).containsEntry("node",22).containsEntry("python",3).containsEntry("browser",0);}
    @Test void anInstalledGuideCanHaveIncompatibleRuntime(){
        var skills=mock(SkillService.class);var runtime=mock(RuntimeService.class);when(skills.catalog(OWNER)).thenReturn(List.of(Map.of("id","custom","name","guide","enabled",true,"content","Requires Node 22+")));
        when(runtime.probe("node")).thenReturn(Map.of("installed",true,"version","v20.17.0","managed",false));
        var result=new SkillDependencyService(skills,runtime).check(OWNER,"custom");assertThat(result).containsEntry("installed",true).containsEntry("status","needs_setup");
    }
    @Test void pythonMinorVersionIsCheckedAsWellAsMajor(){
        var skills=mock(SkillService.class);var runtime=mock(RuntimeService.class);when(skills.catalog(OWNER)).thenReturn(List.of(Map.of("id","py","name","py","enabled",true,"content","Requires Python >=3.10")));
        when(runtime.probe("python")).thenReturn(Map.of("installed",true,"version","Python 3.9.12","managed",false));
        assertThat(new SkillDependencyService(skills,runtime).check(OWNER,"py")).containsEntry("status","needs_setup");
    }
}
