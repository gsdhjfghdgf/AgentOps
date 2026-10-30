package com.agentops.api;

import com.agentops.domain.AgentDomain.*;
import com.agentops.domain.Primitives.*;
import com.agentops.event.RunEventService;
import com.agentops.infra.Json;
import com.agentops.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.*;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SteeringServiceTest {
    @Mock RunRepo runs;
    @Mock SessionRepo sessions;
    @Mock MessageRepo messages;
    @Mock CommandRepo commands;
    @Mock RunEventService events;
    SteeringService service;
    final UUID command=new UUID(0,99);
    @BeforeEach void setup() {
        service=new SteeringService(runs,sessions,messages,commands,events,new Json(),Clock.fixed(NOW,ZoneOffset.UTC));
    }
    @Test void queuesRequirementsWithoutBreakingAnInFlightToolTranscript() {
        when(runs.lockByOwner(OWNER,RUN)).thenReturn(Optional.of(row(RunStatus.RUNNING,1,"hash")));
        assertThat(service.submit(OWNER,RUN,command,"改成中文表格")).containsEntry("status","received");
        verify(commands).insertCommand(eq(OWNER.tenantId()),eq(OWNER.userId()),eq(SESSION),eq(RUN),eq(command),
                eq("steer"),contains("改成中文表格"),isNull(),eq(7L),isNull());
        verifyNoInteractions(messages,sessions);
    }
    @Test void duplicateCommandReturnsItsOriginalReceipt() {
        when(runs.lockByOwner(OWNER,RUN)).thenReturn(Optional.of(row(RunStatus.SUCCEEDED,1,"hash")));
        when(commands.findCommand(OWNER.tenantId(),RUN,command)).thenReturn(Optional.of(new CommandRepo.CommandRow(
                command,1,"steer","{\"text\":\"中文\"}","applied","applied",null)));
        assertThat(service.submit(OWNER,RUN,command,"中文")).containsEntry("status","applied");
        verify(commands,never()).insertCommand(any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }
    @Test void duplicateKeyWithChangedTextIsRejected() {
        when(runs.lockByOwner(OWNER,RUN)).thenReturn(Optional.of(row(RunStatus.RUNNING,1,"hash")));
        when(commands.findCommand(OWNER.tenantId(),RUN,command)).thenReturn(Optional.of(new CommandRepo.CommandRow(
                command,1,"steer","{\"text\":\"中文\"}","received",null,null)));
        assertThatThrownBy(() -> service.submit(OWNER,RUN,command,"英文")).isInstanceOf(RunApplicationService.ConflictException.class);
    }
    @Test void completedTaskDoesNotAcceptNewSteering() {
        when(runs.lockByOwner(OWNER,RUN)).thenReturn(Optional.of(row(RunStatus.SUCCEEDED,1,"hash")));
        assertThatThrownBy(() -> service.submit(OWNER,RUN,command,"中文")).isInstanceOf(RunApplicationService.ConflictException.class);
    }
    @Test void foreignRunIsHidden() {
        assertThatThrownBy(() -> service.submit(OWNER,RUN,command,"中文")).isInstanceOf(RunApplicationService.NotFoundException.class);
        verifyNoInteractions(commands,messages);
    }

    RunSnapshot snapshot(Phase phase) {
        return new RunSnapshot(new RunKey(OWNER,SESSION,RUN),RunStatus.RUNNING,phase,7,1,1,
                NOW.plusSeconds(1800),null,null,WorkingState.empty("原始任务"),null,null,null,0);
    }

    @Test void toolBatchIsNeverSplitByASteeringMessage() {
        var worker=new UUID(0,10);
        assertThat(service.applyAtBoundary(new Lease(new RunKey(OWNER,SESSION,RUN),worker,1,NOW.plusSeconds(60)),
                snapshot(Phase.TOOLS))).isFalse();
        verifyNoInteractions(messages,commands,runs);
    }

    @Test void requirementsAreAppliedAtModelBoundaryAndAcknowledged() {
        var worker=new UUID(0,10);
        var lease=new Lease(new RunKey(OWNER,SESSION,RUN),worker,1,NOW.plusSeconds(60));
        when(runs.lockByOwner(OWNER,RUN)).thenReturn(Optional.of(leasedRow(Phase.FINALIZE,worker)));
        when(commands.pendingSteering(OWNER.tenantId(),RUN)).thenReturn(List.of(new CommandRepo.CommandRow(
                command,1,"steer","{\"text\":\"中文表格\"}","received",null,null)));
        when(sessions.allocateSeq(OWNER,SESSION)).thenReturn(9L);
        when(runs.advancePhase(lease,7,Phase.MODEL)).thenReturn(true);
        assertThat(service.applyAtBoundary(lease,snapshot(Phase.FINALIZE))).isTrue();
        var saved=org.mockito.ArgumentCaptor.forClass(MessageRepo.MessageRow.class);
        verify(messages).insert(saved.capture());
        assertThat(new Json().tree(saved.getValue().payloadJson()).path("content").asText()).isEqualTo("中文表格");
        verify(commands).applyCommand(OWNER.tenantId(),RUN,command,"applied",NOW);
        verify(events).append(eq(OWNER),eq(SESSION),eq(RUN),eq("run.command_applied"),anyMap());
    }
}
