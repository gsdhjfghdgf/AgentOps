package com.agentops.operations;

import com.agentops.infra.Json;
import com.agentops.persistence.RunRepo;
import com.agentops.project.ProjectService;
import com.agentops.api.RunApplicationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.*;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OperationsServiceTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);final RunRepo runs=mock(RunRepo.class);final ProjectService projects=mock(ProjectService.class);
    final OperationsService service=new OperationsService(db,runs,new Json(),projects);
    @BeforeEach void setup(){when(runs.findByIdOwner(OWNER,RUN)).thenReturn(Optional.of(rowWithConfig("{}")));}
    @Test void completedStepsCannotBeRemovedOrRewritten(){
        when(db.queryForList(startsWith("SELECT revision"),eq(OWNER.tenantId()),eq(OWNER.userId()),eq(RUN))).thenReturn(List.of(Map.of("revision",1L,"steps","[{\"id\":\"a\",\"title\":\"已做完\",\"status\":\"done\"}]","updated_at",NOW)));
        assertThatThrownBy(()->service.savePlan(OWNER,RUN,1,"[{\"id\":\"a\",\"title\":\"修改旧步骤\",\"status\":\"pending\"}]")).hasMessageContaining("只能调整");
        assertThatThrownBy(()->service.savePlan(OWNER,RUN,1,"[{\"id\":\"b\",\"title\":\"后续\",\"status\":\"pending\"}]")).hasMessageContaining("不能删除");
    }
    @Test void stalePlanCannotOverwriteANewerRevision(){
        assertThatThrownBy(()->service.savePlan(OWNER,RUN,4,"[{\"id\":\"a\",\"title\":\"后续\",\"status\":\"pending\"}]")).isInstanceOf(RunApplicationService.ConflictException.class);
    }
    @Test void invalidOrSimultaneousActiveStepsAreRejected(){
        assertThatThrownBy(()->service.savePlan(OWNER,RUN,0,"[{\"id\":\"a\",\"title\":\"一\",\"status\":\"doing\"},{\"id\":\"b\",\"title\":\"二\",\"status\":\"doing\"}]")).hasMessageContaining("同时只能");
    }
    @Test void ownerGuardRunsBeforePlanOrPermissionsAreRead(){
        when(runs.findByIdOwner(OWNER,RUN)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.plan(OWNER,RUN)).isInstanceOf(RunApplicationService.NotFoundException.class);
        assertThatThrownBy(()->service.permissions(OWNER,RUN)).isInstanceOf(RunApplicationService.NotFoundException.class);
        verifyNoInteractions(db);
    }
}
