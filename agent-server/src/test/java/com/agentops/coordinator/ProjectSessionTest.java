package com.agentops.coordinator;

import com.agentops.approval.ApprovalService;
import com.agentops.budget.BudgetService;
import com.agentops.domain.Primitives.CompletionContract;
import com.agentops.infra.Json;
import com.agentops.persistence.*;
import com.agentops.project.ProjectRepo;
import com.agentops.event.RunEventService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProjectSessionTest {
    final RunRepo runs=mock(RunRepo.class);
    final SessionRepo sessions=mock(SessionRepo.class);
    final ProjectRepo projects=mock(ProjectRepo.class);
    final TransactionTemplate tx=mock(TransactionTemplate.class);
    final RunCoordinator coordinator=new RunCoordinator(runs,sessions,mock(MessageRepo.class),mock(StateRepo.class),
            mock(ExecutionRepo.class),mock(BudgetService.class),mock(ApprovalService.class),mock(CommandRepo.class),
            mock(OutboxRepo.class),mock(RunEventService.class),new Json(),tx,projects);

    @SuppressWarnings("unchecked")
    void owned(UUID projectId) {
        when(tx.execute(any())).thenAnswer(i->((TransactionCallback<Object>)i.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
        when(sessions.lockOwned(OWNER,SESSION)).thenReturn(true);
        when(sessions.findById(OWNER,SESSION)).thenReturn(Optional.of(new SessionRepo.SessionRow(OWNER.tenantId(),OWNER.userId(),SESSION,"chat",0,0,NOW,NOW,false,null,"chat",projectId)));
    }
    void create(Map<String,Object> config) {
        coordinator.createRun(OWNER,SESSION,"request","hash","test",CompletionContract.defaults("test"),config,NOW.plusSeconds(60));
    }
    @Test void everyTurnUsesTheSessionProjectRegardlessOfGlobalSelectionOrClientConfig() {
        UUID id=UUID.randomUUID();owned(id);
        when(projects.get(OWNER,id)).thenReturn(new ProjectRepo.Project(id,"A","D:/A","http://localhost:5173"));
        create(Map.of("projectId",UUID.randomUUID().toString(),"projectPreviewOrigin","http://localhost:9000"));
        var config=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(runs).insertNew(eq(OWNER),eq(SESSION),any(),eq("request"),eq("hash"),config.capture(),anyString(),any());
        assertThat(new Json().tree(config.getValue()).path("projectId").asText()).isEqualTo(id.toString());
        assertThat(new Json().tree(config.getValue()).path("projectPreviewOrigin").asText()).isEqualTo("http://localhost:5173");
        verify(projects,never()).selected(any());
    }
    @Test void ordinaryChatsNeverInheritSelectedOrClientSuppliedProjects() {
        owned(null);create(Map.of("projectId",UUID.randomUUID().toString(),"projectPreviewOrigin","http://localhost:9000"));
        var config=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(runs).insertNew(eq(OWNER),eq(SESSION),any(),eq("request"),eq("hash"),config.capture(),anyString(),any());
        assertThat(new Json().tree(config.getValue()).has("projectId")).isFalse();
        assertThat(new Json().tree(config.getValue()).has("projectPreviewOrigin")).isFalse();
        verifyNoInteractions(projects);
    }
    @Test void missingOrForeignProjectCannotCreateATask() {
        UUID id=UUID.randomUUID();owned(id);
        when(projects.get(OWNER,id)).thenThrow(new com.agentops.api.RunApplicationService.NotFoundException("项目不存在"));
        assertThatThrownBy(()->create(Map.of())).hasMessage("项目不存在");
        verify(runs,never()).insertNew(any(),any(),any(),anyString(),anyString(),anyString(),anyString(),any());
    }
}
