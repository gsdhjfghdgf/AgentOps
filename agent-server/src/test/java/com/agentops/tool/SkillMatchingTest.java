package com.agentops.tool;
import com.agentops.persistence.CapabilityRepo;
import com.agentops.skill.SkillService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class SkillMatchingTest {
    @Test void chineseKeywordsMatchAndDisabledSkillIsExcluded() {
        var repo=mock(CapabilityRepo.class);when(repo.skills(OWNER)).thenReturn(List.of());when(repo.preferences(OWNER,"skill")).thenReturn(Map.of());
        var service=new SkillService(repo);
        assertThat(service.matchAll(OWNER,"请整理这次会议纪要")).extracting(s->s.meta().name()).contains("meeting-notes");
        when(repo.preferences(OWNER,"skill")).thenReturn(Map.of("builtin:meeting-notes",new CapabilityRepo.Preference(false,false)));
        assertThat(service.matchAll(OWNER,"请整理这次会议纪要")).extracting(s->s.meta().name()).doesNotContain("meeting-notes");
    }
    @Test void explicitCustomSkillHasPriorityWithoutChangingToolPermissions() {
        var repo=mock(CapabilityRepo.class);when(repo.preferences(OWNER,"skill")).thenReturn(Map.of());
        when(repo.skills(OWNER)).thenReturn(List.of(new CapabilityRepo.UserSkill(UUID.randomUUID(),"custom-workflow","custom","流程","用户自定义流程","")));
        var service=new SkillService(repo);
        assertThat(service.matchAll(OWNER,"使用 $custom-workflow 阅读文档整理会议代码资料").get(0).meta().name()).isEqualTo("custom-workflow");
        verify(repo,never()).preference(any(),eq("tool"),anyString(),anyBoolean(),anyBoolean());
    }
    @Test void explicitlyActivatedSkillLoadsWithoutKeywordAndRemainsOwnerScoped() {
        var repo=mock(CapabilityRepo.class);var id=UUID.randomUUID();
        when(repo.skills(OWNER)).thenReturn(List.of(new CapabilityRepo.UserSkill(id,"design-guide","guide","english-only","ACTUAL_GUIDANCE","")));
        when(repo.activeSkills(OWNER,RUN)).thenReturn(List.of("user:"+id));
        var service=new SkillService(repo);
        assertThat(service.activate(OWNER,RUN,"design-guide")).containsEntry("loaded",true);
        assertThat(service.matchForRun(OWNER,RUN,"请美化这个页面")).extracting(s->s.content()).contains("ACTUAL_GUIDANCE");
        when(repo.preferences(OWNER,"skill")).thenReturn(Map.of("user:"+id,new CapabilityRepo.Preference(false,false)));
        assertThat(service.matchForRun(OWNER,RUN,"请美化这个页面")).extracting(s->s.content()).doesNotContain("ACTUAL_GUIDANCE");
        assertThatThrownBy(()->service.activate(OWNER,RUN,"design-guide")).hasMessageContaining("禁用");
    }
}
