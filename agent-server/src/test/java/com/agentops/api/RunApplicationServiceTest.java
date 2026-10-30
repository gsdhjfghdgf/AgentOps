package com.agentops.api;

import com.agentops.budget.BudgetService;
import com.agentops.config.AgentProperties;
import com.agentops.coordinator.RunCoordinator;
import com.agentops.domain.AgentDomain.MessageRole;
import com.agentops.domain.AgentDomain.RunStatus;
import com.agentops.infra.Json;
import com.agentops.persistence.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RunApplicationServiceTest {
    @Test void modesAreCanonicalizedPinnedAndIncludedInIdempotency() {
        when(coordinator.createRun(eq(OWNER),eq(SESSION),anyString(),anyString(),anyString(),any(),anyMap(),any()))
                .thenReturn(new RunCoordinator.CreatedRun(RUN,false));
        for(String mode:Arrays.asList(null,"BUILD","plan","chat"))
            service.createRun(OWNER,SESSION,new RunApplicationService.CreateRunRequest("key","解释Java",null,null,null,null,mode,null));
        var hashes=ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked") var configs=ArgumentCaptor.forClass(Map.class);
        verify(coordinator,times(4)).createRun(eq(OWNER),eq(SESSION),eq("key"),hashes.capture(),anyString(),any(),configs.capture(),any());
        assertThat(hashes.getAllValues().get(0)).isEqualTo(hashes.getAllValues().get(1));
        assertThat(hashes.getAllValues().subList(1,4)).doesNotHaveDuplicates();
        assertThat(configs.getAllValues().get(2)).containsEntry("mode","plan");
        assertThat(configs.getAllValues().get(3)).containsEntry("mode","chat");
    }
    @Test void invalidModeAndHandoffInChatCannotCreateRuns() {
        for(String mode:List.of("automatic","chat")) {
            var req=new RunApplicationService.CreateRunRequest("key","执行",null,null,null,null,mode,RUN);
            assertThatThrownBy(()->service.createRun(OWNER,SESSION,req)).isInstanceOf(RunApplicationService.BadRequestException.class);
        }
        verifyNoInteractions(coordinator,runs);
    }
    @Test void buildSnapshotsCompletedPlanInSameOwnedConversation() {
        var source=rowWithConfig("{\"mode\":\"plan\"}");
        // The source must have actually succeeded; a running plan is not executable.
        var complete=new RunRepo.RunRow(source.tenantId(),source.userId(),source.sessionId(),source.id(),
                source.requestKey(),source.requestHash(),RunStatus.SUCCEEDED,source.phase(),source.stateVersion(),source.checkpointVersion(),
                source.workerId(),source.workerEpoch(),source.leaseUntil(),source.deadlineAt(),null,source.nextAttemptAt(),
                source.eventSeq(),null,null,null,null,source.configSnapshotJson(),source.completionContractJson(),
                source.repairCount(),source.replanCount(),source.tokenUsed(),source.createdAt(),source.startedAt(),NOW);
        when(runs.findByIdOwner(OWNER,RUN)).thenReturn(Optional.of(complete));
        when(messages.lastAnswer(OWNER,SESSION,RUN)).thenReturn(Optional.of("1. 阅读代码\n2. 修复问题\n3. 验证结果"));
        when(coordinator.createRun(eq(OWNER),eq(SESSION),anyString(),anyString(),anyString(),any(),anyMap(),any()))
                .thenReturn(new RunCoordinator.CreatedRun(UUID.randomUUID(),false));
        service.createRun(OWNER,SESSION,new RunApplicationService.CreateRunRequest("execute","执行计划",null,null,null,null,"build",RUN));
        @SuppressWarnings("unchecked") var config=ArgumentCaptor.forClass(Map.class);
        verify(coordinator).createRun(eq(OWNER),eq(SESSION),anyString(),anyString(),eq("执行计划"),any(),config.capture(),any());
        assertThat(config.getValue()).containsEntry("mode","build").containsEntry("planRunId",RUN.toString())
                .containsEntry("approvedPlan","1. 阅读代码\n2. 修复问题\n3. 验证结果");
    }
    @Test void unfinishedOrForeignPlansCannotBeHandedOff() {
        var req=new RunApplicationService.CreateRunRequest("execute","执行",null,null,null,null,"build",RUN);
        assertThatThrownBy(()->service.createRun(OWNER,SESSION,req)).isInstanceOf(RunApplicationService.NotFoundException.class);
        when(runs.findByIdOwner(OWNER,RUN)).thenReturn(Optional.of(rowWithConfig("{\"mode\":\"plan\"}")));
        assertThatThrownBy(()->service.createRun(OWNER,SESSION,req)).isInstanceOf(RunApplicationService.ConflictException.class);
        assertThatThrownBy(()->service.createRun(OWNER,UUID.randomUUID(),req)).isInstanceOf(RunApplicationService.NotFoundException.class);
        verifyNoInteractions(coordinator,messages);
    }
    @Test void sidebarStatusesAreFetchedAsOneOwnerScopedBatch() {
        var other=UUID.randomUUID();
        var a=new SessionRepo.SessionRow(OWNER.tenantId(),OWNER.userId(),SESSION,"A",0,0,NOW,NOW,false,null,"chat");
        var b=new SessionRepo.SessionRow(OWNER.tenantId(),OWNER.userId(),other,"B",0,0,NOW,NOW,false,null,"chat");
        when(sessions.search(OWNER,100,"",false)).thenReturn(List.of(a,b));
        when(runs.activeForSessions(OWNER,List.of(SESSION,other))).thenReturn(Map.of(
                SESSION,new RunRepo.ActiveSessionRun(SESSION,RUN,"running"),
                other,new RunRepo.ActiveSessionRun(other,QUESTION,"waiting_approval")));
        var sidebar=service.searchSessions(OWNER,100,"",false);
        assertThat(sidebar).extracting(RunApplicationService.SessionView::activeRunStatus).containsExactly("running","waiting_approval");
        verify(runs).activeForSessions(OWNER,List.of(SESSION,other));
        verifyNoMoreInteractions(runs);
    }
    @Mock SessionRepo sessions;
    @Mock RunRepo runs;
    @Mock MessageRepo messages;
    @Mock RunCoordinator coordinator;
    @Mock BudgetService budget;
    RunApplicationService service;

    @BeforeEach void setup() {
        var props = new AgentProperties(new AgentProperties.Model("mock", "", "", "mock", "hash", 1024, 60, 2),
                new AgentProperties.Budget(8, 16, 40000, 2000, 1000000, "CNY", 30),
                null, null, null, null, null, null);
        service = new RunApplicationService(sessions, runs, messages, coordinator, budget, props, new Json(),
                mock(com.agentops.model.ModelSettingsService.class,invocation ->
                        "current".equals(invocation.getMethod().getName()) ? com.agentops.model.ModelSettingsService.defaults(props)
                                : org.mockito.Mockito.RETURNS_DEFAULTS.answer(invocation)),mock(com.agentops.operations.TaskProgressService.class));
    }

    void ownedSession() {
        when(sessions.findById(OWNER, SESSION)).thenReturn(Optional.of(new SessionRepo.SessionRow(
                OWNER.tenantId(), OWNER.userId(), SESSION, "test", 3, 3, NOW, NOW, false, null, "chat")));
    }

    @Test void budgetContinuationKeepsSameRunAndCheckpoint() {
        var stopped=row(RunStatus.BUDGET_EXCEEDED,4,"hash");
        when(runs.findByIdOwner(OWNER,RUN)).thenReturn(Optional.of(stopped));
        when(sessions.lockOwned(OWNER,SESSION)).thenReturn(true);
        when(runs.lockByOwner(OWNER,RUN)).thenReturn(Optional.of(stopped));
        when(runs.listBySession(OWNER,SESSION,1)).thenReturn(List.of(stopped));
        when(runs.resumeBudgetTask(eq(OWNER),eq(RUN),eq(7L),any())).thenReturn(true);
        assertThat(service.continueBudgetTask(OWNER,RUN)).containsEntry("runId",RUN.toString()).containsEntry("status","queued");
        verify(budget).extendRun(OWNER,RUN);
        verifyNoInteractions(messages,coordinator);
    }
    @Test void continuingAnotherUsersRunIsRejected() {
        assertThatThrownBy(()->service.continueBudgetTask(OWNER,RUN)).isInstanceOf(RunApplicationService.NotFoundException.class);
        verifyNoInteractions(budget,messages,coordinator);
    }
    @Test void continuingBehindANewerTaskIsRejectedBeforeBudgetChanges() {
        var stopped=row(RunStatus.BUDGET_EXCEEDED,4,"hash");
        when(runs.findByIdOwner(OWNER,RUN)).thenReturn(Optional.of(stopped));
        when(sessions.lockOwned(OWNER,SESSION)).thenReturn(true);
        when(runs.lockByOwner(OWNER,RUN)).thenReturn(Optional.of(stopped));
        when(runs.listBySession(OWNER,SESSION,1)).thenReturn(List.of());
        assertThatThrownBy(()->service.continueBudgetTask(OWNER,RUN)).isInstanceOf(RunApplicationService.ConflictException.class);
        verifyNoInteractions(budget,messages,coordinator);
    }

    MessageRepo.MessagePage message(long seq) {
        return new MessageRepo.MessagePage(new MessageRepo.MessageRow(OWNER.tenantId(), OWNER.userId(), SESSION,
                seq, RUN, MessageRole.ASSISTANT,
                "{\"content\":\"hello\",\"toolCalls\":[{\"id\":\"c1\",\"name\":\"sales_query\",\"arguments\":{}}]}",
                1, null, null, NOW), seq);
    }

    @Test void historyReturnsChronologicalPageAndAccurateCursor() {
        ownedSession();
        when(messages.pageBefore(OWNER, SESSION, Long.MAX_VALUE, 3))
                .thenReturn(List.of(message(3), message(2), message(1)));
        var page = service.history(OWNER, SESSION, null, 2);
        assertThat(page.messages()).extracting(RunApplicationService.MessageView::seq).containsExactly(2L, 3L);
        assertThat(page.nextBeforeSeq()).isEqualTo(2L);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.messages().get(0).toolCalls()).hasSize(1);
    }

    @Test void exactFullLastPageDoesNotClaimMoreHistory() {
        ownedSession();
        when(messages.pageBefore(OWNER, SESSION, 3, 3)).thenReturn(List.of(message(2), message(1)));
        assertThat(service.history(OWNER, SESSION, 3L, 2).hasMore()).isFalse();
    }

    @Test void historyRejectsForeignSessionBeforeReadingMessages() {
        assertThatThrownBy(() -> service.history(OWNER, SESSION, null, 20))
                .isInstanceOf(RunApplicationService.NotFoundException.class);
        verifyNoInteractions(messages);
    }

    @Test void supplementRejectsStaleQuestionWithoutWritingMessage() {
        when(runs.lockByOwner(OWNER, RUN)).thenReturn(Optional.of(row(RunStatus.WAITING_USER, 1, "hash")));
        assertThatThrownBy(() -> service.supplement(OWNER, SESSION, RUN,
                new RunApplicationService.SupplementRequest(UUID.randomUUID().toString(), "本月")))
                .isInstanceOf(RunApplicationService.ConflictException.class);
        verifyNoInteractions(messages, coordinator);
    }

    @Test void supplementRejectsExpiredOrCancelledTaskWithoutAppending() {
        when(runs.lockByOwner(OWNER, RUN)).thenReturn(Optional.of(row(RunStatus.WAITING_USER, 1, "hash")));
        assertThatThrownBy(() -> service.supplement(OWNER, SESSION, RUN,
                new RunApplicationService.SupplementRequest(QUESTION.toString(), "本月")))
                .isInstanceOf(RunApplicationService.ConflictException.class);
        verifyNoInteractions(messages, sessions);
    }

    @Test void supplementPersistsAnswerWithMatchingQuestion() {
        when(runs.lockByOwner(OWNER, RUN)).thenReturn(Optional.of(row(RunStatus.WAITING_USER, 1, "hash")));
        when(coordinator.wakeToQueued(OWNER.tenantId(), RUN, 7, "user_supplemented")).thenReturn(true);
        when(coordinator.commands()).thenReturn(mock(CommandRepo.class));
        when(sessions.allocateSeq(OWNER, SESSION)).thenReturn(4L);
        var result = service.supplement(OWNER, SESSION, RUN,
                new RunApplicationService.SupplementRequest(QUESTION.toString(), "本月"));
        assertThat(result).containsEntry("ok", true).containsEntry("seq", 4L);
        var saved = ArgumentCaptor.forClass(MessageRepo.MessageRow.class);
        verify(messages).insert(saved.capture());
        assertThat(new Json().tree(saved.getValue().payloadJson()).path("content").asText()).isEqualTo("本月");
    }

    @Test void supplementCannotTargetAnotherSession() {
        when(runs.lockByOwner(OWNER, RUN)).thenReturn(Optional.of(row(RunStatus.WAITING_USER, 1, "hash")));
        assertThatThrownBy(() -> service.supplement(OWNER, UUID.randomUUID(), RUN,
                new RunApplicationService.SupplementRequest(QUESTION.toString(), "本月")))
                .isInstanceOf(RunApplicationService.NotFoundException.class);
        verifyNoInteractions(messages, coordinator);
    }

    @Test void taskHashIncludesContractAndCanonicalizesNestedMapOrder() {
        when(coordinator.createRun(eq(OWNER), eq(SESSION), anyString(), anyString(), anyString(), any(), anyMap(), any()))
                .thenReturn(new RunCoordinator.CreatedRun(RUN, false));
        var first = new LinkedHashMap<String, Object>(); first.put("currency", "CNY"); first.put("period", 7);
        var reverse = new LinkedHashMap<String, Object>(); reverse.put("period", 7); reverse.put("currency", "CNY");
        for (var requirements : List.of(first, reverse, Map.<String, Object>of("period", 30))) {
            service.createRun(OWNER, SESSION, new RunApplicationService.CreateRunRequest(
                    "key", "生成报告", "report", requirements, List.of(), false));
        }
        var hashes = ArgumentCaptor.forClass(String.class);
        verify(coordinator, times(3)).createRun(eq(OWNER), eq(SESSION), eq("key"), hashes.capture(),
                anyString(), any(), anyMap(), any());
        assertThat(hashes.getAllValues().get(0)).isEqualTo(hashes.getAllValues().get(1))
                .isNotEqualTo(hashes.getAllValues().get(2));
    }

    @Test void repeatedTextWithoutRequestKeyStartsIndependentTasks() {
        when(coordinator.createRun(eq(OWNER), eq(SESSION), anyString(), anyString(), anyString(), any(), anyMap(), any()))
                .thenReturn(new RunCoordinator.CreatedRun(RUN, false));
        var req = new RunApplicationService.CreateRunRequest(null, "你好", null, null, null, null);
        service.createRun(OWNER, SESSION, req); service.createRun(OWNER, SESSION, req);
        var keys = ArgumentCaptor.forClass(String.class);
        verify(coordinator, times(2)).createRun(eq(OWNER), eq(SESSION), keys.capture(), anyString(), anyString(), any(), anyMap(), any());
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();
    }

    @Test void clearHistoryOnlyMarksOwnedSessionsAndRetainsOtherData() {
        when(sessions.clearHistory(OWNER)).thenReturn(12);
        assertThat(service.clearSessionHistory(OWNER)).isEqualTo(12);
        verify(sessions).lockHistory(OWNER);
        verifyNoInteractions(messages,budget,coordinator);
    }

    @Test void clearHistoryRefusesUnfinishedTasks() {
        when(runs.hasOpenForOwner(OWNER)).thenReturn(true);
        assertThatThrownBy(() -> service.clearSessionHistory(OWNER)).isInstanceOf(RunApplicationService.ConflictException.class);
        verify(sessions,never()).clearHistory(any());
    }

    @Test void deleteCannotRemoveAnotherUsersSession() {
        assertThatThrownBy(() -> service.deleteSession(OWNER,SESSION)).isInstanceOf(RunApplicationService.NotFoundException.class);
        verify(sessions,never()).softDelete(any(),any());
    }

    @Test void renameValidatesOwnershipAndBlankNames() {
        when(sessions.lockOwned(OWNER,SESSION)).thenReturn(true);
        assertThatThrownBy(() -> service.updateSession(OWNER,SESSION,new RunApplicationService.SessionUpdate(" ",null,null)))
                .isInstanceOf(RunApplicationService.BadRequestException.class);
        verify(sessions,never()).rename(any(),any(),any());
    }
}
