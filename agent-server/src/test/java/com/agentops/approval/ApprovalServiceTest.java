package com.agentops.approval;

import com.agentops.api.RunApplicationService;
import com.agentops.domain.AgentDomain.RunStatus;
import com.agentops.event.RunEventService;
import com.agentops.infra.Json;
import com.agentops.persistence.ApprovalRepo;
import com.agentops.persistence.RunRepo;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {
    @Mock ApprovalRepo repo;
    @Mock RunRepo runs;
    @Mock RunEventService events;
    ApprovalService service;
    final UUID approval = new UUID(0, 9);

    @BeforeEach void setup() {
        service = new ApprovalService(repo, runs, events, new Json(), Clock.fixed(NOW, ZoneOffset.UTC));
    }
    ApprovalRepo.ApprovalRow approval(String state) {
        return new ApprovalRepo.ApprovalRow(approval, RUN, QUESTION, "call1", "send_email", "digest",
                state, null, null, NOW.plusSeconds(60), null, null);
    }
    void pending() {
        when(repo.findById(OWNER.tenantId(), OWNER.userId(), approval)).thenReturn(Optional.of(approval("pending")));
    }

    @Test void anotherUsersApprovalIsNeitherVisibleNorDecidable() {
        assertThat(service.view(OWNER, approval)).isEmpty();
        assertThat(service.decide(OWNER, approval, "approved", null).ok()).isFalse();
        verify(repo, never()).decide(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(runs, events);
    }

    @Test void listingRequiresOwnershipOfTheRun() {
        assertThatThrownBy(() -> service.listPending(OWNER, RUN)).isInstanceOf(RunApplicationService.NotFoundException.class);
        verifyNoInteractions(repo);
    }

    @Test void cancelledTaskRejectsLateApproval() {
        pending();
        when(runs.lockByOwner(OWNER, RUN)).thenReturn(Optional.of(row(RunStatus.CANCELLED, 1, "hash")));
        assertThatThrownBy(() -> service.decide(OWNER, approval, "approved", null))
                .isInstanceOf(RunApplicationService.ConflictException.class);
        verify(repo, never()).decide(any(), any(), any(), any(), any(), any());
    }

    @Test void approvalRequeuesTaskAndEmitsBothEvents() {
        pending();
        when(runs.lockByOwner(OWNER, RUN)).thenReturn(Optional.of(row(RunStatus.WAITING_APPROVAL, 1, "hash")));
        when(repo.decide(OWNER.tenantId(), OWNER.userId(), approval, "approved", null, NOW))
                .thenReturn(Optional.of(approval("approved")));
        when(runs.wakeToQueued(OWNER.tenantId(), RUN, 7, "approval_granted")).thenReturn(true);
        assertThat(service.decide(OWNER, approval, "approved", null).status()).isEqualTo("approved");
        verify(events).append(eq(OWNER), eq(SESSION), eq(RUN), eq("approval.decided"), anyMap());
        verify(events).append(eq(OWNER), eq(SESSION), eq(RUN), eq("run.queued"), anyMap());
    }

    @Test void invalidDecisionDoesNotBecomeAnAccidentalRejection() {
        assertThatThrownBy(() -> service.decide(OWNER, approval, "approve_typo", null))
                .isInstanceOf(RunApplicationService.BadRequestException.class);
        verifyNoInteractions(repo, runs, events);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"web_search","command_run"})
    void approvingTaskPermissionGrantsOnlyThatCapability(String tool) {
        var pending=new ApprovalRepo.ApprovalRow(approval,RUN,QUESTION,"call1",tool,"digest","pending",null,null,NOW.plusSeconds(60),null,null);
        var approved=new ApprovalRepo.ApprovalRow(approval,RUN,QUESTION,"call1",tool,"digest","approved",null,null,NOW.plusSeconds(60),null,null);
        when(repo.findById(OWNER.tenantId(),OWNER.userId(),approval)).thenReturn(Optional.of(pending));
        when(runs.lockByOwner(OWNER,RUN)).thenReturn(Optional.of(row(RunStatus.WAITING_APPROVAL,1,"hash")));
        when(repo.decide(OWNER.tenantId(),OWNER.userId(),approval,"approved",null,NOW)).thenReturn(Optional.of(approved));
        when(runs.wakeToQueued(OWNER.tenantId(),RUN,7,"approval_granted")).thenReturn(true);
        assertThat(service.decide(OWNER,approval,"approved",null).ok()).isTrue();
        if("command_run".equals(tool)) {
            verify(runs).grantCommandPermission(OWNER,RUN,approval);
            verify(runs,never()).grantNetworkPermission(any(),any(),any());
        } else {
            verify(runs).grantNetworkPermission(OWNER,RUN,approval);
            verify(runs,never()).grantCommandPermission(any(),any(),any());
        }
    }
}
