package com.agentops.api;

import com.agentops.domain.AgentDomain.*;
import com.agentops.domain.Primitives.*;
import com.agentops.event.RunEventService;
import com.agentops.infra.Json;
import com.agentops.persistence.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RunControlServiceTest {
    final RunRepo runs=mock(RunRepo.class);
    final StateRepo states=mock(StateRepo.class);
    final ExecutionRepo execution=mock(ExecutionRepo.class);
    final MessageRepo messages=mock(MessageRepo.class);
    final SessionRepo sessions=mock(SessionRepo.class);
    final CommandRepo commands=mock(CommandRepo.class);
    final RunEventService events=mock(RunEventService.class);
    final Json json=new Json();
    final RunControlService service=new RunControlService(runs,states,execution,messages,sessions,commands,events,
            json,Clock.fixed(NOW,ZoneOffset.UTC));
    final UUID request=UUID.randomUUID();
    @Test void activeOperationIsOnlyAskedToPauseAndKeepsItsLeaseUntilBoundary() {
        when(runs.lockByOwner(OWNER,RUN)).thenReturn(Optional.of(row(RunStatus.RUNNING,1,"hash")));
        when(runs.requestPause(OWNER,RUN,7)).thenReturn(true);
        assertThat(service.pause(OWNER,RUN,request)).containsEntry("status","pause_requested");
        verify(runs,never()).pauseIdle(any(),any(),anyLong());
        verifyNoInteractions(execution,states,messages);
    }
    @Test void queuedTaskCanPauseBeforeAnyExecution() {
        when(runs.lockByOwner(OWNER,RUN)).thenReturn(Optional.of(row(RunStatus.QUEUED,1,"hash")));
        when(runs.pauseIdle(OWNER,RUN,7)).thenReturn(true);
        assertThat(service.pause(OWNER,RUN,request)).containsEntry("status","paused");
        verify(runs,never()).requestPause(any(),any(),anyLong());
    }
    @Test void resumeStoresSupplementInSameTaskAndCarriesItsWorkingMemory() {
        var task=row(RunStatus.PAUSED,1,"hash");
        var original=WorkingState.empty("审查项目").withToolBatch("{\"calls\":[{\"id\":\"pending\",\"name\":\"project_write\"}]}","revision",3);
        when(runs.lockByOwner(OWNER,RUN)).thenReturn(Optional.of(task));
        when(states.findLatest(OWNER.tenantId(),RUN)).thenReturn(Optional.of(
                new StateRepo.CheckpointRow(RUN,1,1,json.write(original),NOW)));
        when(sessions.allocateSeq(OWNER,SESSION)).thenReturn(4L,5L);
        when(states.insertCheckpoint(eq(OWNER.tenantId()),eq(OWNER.userId()),eq(SESSION),eq(RUN),eq(2L),anyString())).thenReturn(2L);
        when(runs.resumePaused(OWNER,RUN,7,2)).thenReturn(true);
        assertThat(service.resume(OWNER,RUN,request,"只读检查，不修改文件")).containsEntry("runId",RUN.toString()).containsEntry("status","queued");
        var checkpoint=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(states).insertCheckpoint(eq(OWNER.tenantId()),eq(OWNER.userId()),eq(SESSION),eq(RUN),eq(2L),checkpoint.capture());
        var resumed=json.read(checkpoint.getValue(),WorkingState.class);
        assertThat(resumed.goal()).contains("审查项目","只读检查，不修改文件");
        assertThat(resumed.pendingToolBatchJson()).isNull();
        verify(messages,times(2)).insert(any());
        verify(runs,never()).insertNew(any(),any(),any(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void oldResumeReceiptCannotSubmitSupplementOrResumeTwice() {
        when(runs.lockByOwner(OWNER,RUN)).thenReturn(Optional.of(row(RunStatus.RUNNING,1,"hash")));
        when(commands.findCommand(OWNER.tenantId(),RUN,request)).thenReturn(Optional.of(
                new CommandRepo.CommandRow(request,1,"resume","{\"text\":\"补充\"}","applied",null,null)));
        assertThat(service.resume(OWNER,RUN,request,"补充")).containsEntry("idempotentHit",true);
        verifyNoInteractions(states,execution,messages);
        assertThatThrownBy(()->service.resume(OWNER,RUN,request,"不同内容")).isInstanceOf(RunApplicationService.ConflictException.class);
    }
    @Test void unknownOutcomeCannotBeResumedAndForeignTasksCannotBeControlled() {
        assertThatThrownBy(()->service.pause(OWNER,RUN,request)).isInstanceOf(RunApplicationService.NotFoundException.class);
        when(runs.lockByOwner(OWNER,RUN)).thenReturn(Optional.of(row(RunStatus.PAUSED,1,"hash")));
        var call=mock(ExecutionRepo.ToolCallRow.class);
        when(call.status()).thenReturn("unknown");
        when(execution.listToolCalls(OWNER.tenantId(),RUN)).thenReturn(List.of(call));
        assertThatThrownBy(()->service.resume(OWNER,RUN,request,"")).isInstanceOf(RunApplicationService.ConflictException.class);
        verifyNoInteractions(states,messages);
    }
}
