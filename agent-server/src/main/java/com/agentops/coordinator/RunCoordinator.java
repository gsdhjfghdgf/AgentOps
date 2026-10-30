package com.agentops.coordinator;

import com.agentops.approval.ApprovalService;
import com.agentops.budget.BudgetService;
import com.agentops.domain.AgentDomain.Phase;
import com.agentops.domain.AgentDomain.PolicyVerdict;
import com.agentops.domain.AgentDomain.RunStatus;
import com.agentops.domain.AgentDomain.StepKind;
import com.agentops.domain.ModelDecision;
import com.agentops.domain.Primitives.CompletionContract;
import com.agentops.domain.Primitives.Lease;
import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.Primitives.RunKey;
import com.agentops.domain.Primitives.RunSnapshot;
import com.agentops.domain.Primitives.WorkingState;
import com.agentops.domain.ResultTypes.CandidateAnswer;
import com.agentops.domain.ResultTypes.Usage;
import com.agentops.domain.ToolTypes.PolicyDecision;
import com.agentops.domain.ToolTypes.ToolIntent;
import com.agentops.domain.ToolTypes.ToolResult;
import com.agentops.event.RunEventService;
import com.agentops.infra.Json;
import com.agentops.persistence.CommandRepo;
import com.agentops.persistence.ExecutionRepo;
import com.agentops.persistence.MessageRepo;
import com.agentops.persistence.OutboxRepo;
import com.agentops.persistence.RunRepo;
import com.agentops.persistence.SessionRepo;
import com.agentops.persistence.StateRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 任务协调器（文档 16.6 事务边界 / 附录 35 RunCoordinator）。
 *
 * 每个方法都是短事务；网络调用绝不放在事务内。
 * 所有推进类操作都校验：owner + worker + epoch + state_version + 租约 + 取消 + 截止时间。
 */
@Service
public class RunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RunCoordinator.class);

    private final RunRepo runs;
    private final SessionRepo sessions;
    private final MessageRepo messages;
    private final StateRepo state;
    private final ExecutionRepo execution;
    private final BudgetService budget;
    private final ApprovalService approvals;
    private final CommandRepo commands;
    private final OutboxRepo outbox;
    private final RunEventService events;
    private final Json json;
    private final TransactionTemplate tx;
    private final com.agentops.project.ProjectRepo projects;

    public RunCoordinator(RunRepo runs, SessionRepo sessions, MessageRepo messages, StateRepo state,
                          ExecutionRepo execution, BudgetService budget, ApprovalService approvals,
                          CommandRepo commands, OutboxRepo outbox, RunEventService events,
                          Json json, TransactionTemplate tx,com.agentops.project.ProjectRepo projects) {
        this.runs = runs;
        this.sessions = sessions;
        this.messages = messages;
        this.state = state;
        this.execution = execution;
        this.budget = budget;
        this.approvals = approvals;
        this.commands = commands;
        this.outbox = outbox;
        this.events = events;
        this.json = json;
        this.tx = tx;
        this.projects=projects;
    }

    public record AttemptHandle(UUID attemptId, int attemptNo) {
    }

    public record ToolPrepare(String outcome, UUID approvalId, UUID attemptId) {
        /** outcome: OK / NEED_APPROVAL / DENIED / BUDGET_EXCEEDED / CANCELLED / IDEMPOTENT_REPLAY */
    }

    // ==================== T1 创建（文档 16.6 T1） ====================

    public record CreatedRun(UUID runId, boolean idempotentHit) {
    }

    /** 会话已有未结束任务。 */
    public static class BusyException extends RuntimeException {
        public BusyException(String message) {
            super(message);
        }
    }

    public static class SessionNotFoundException extends RuntimeException {
        public SessionNotFoundException() { super("会话不存在"); }
    }

    /**
     * T1 同事务：归属检查、请求幂等、run、user 消息、初始检查点、事件、契约 v1。
     */
    public CreatedRun createRun(Principal owner, UUID sessionId, String requestKey, String requestHash,
                                String userMessage, CompletionContract contract,
                                Map<String, Object> configSnapshot, Instant deadlineAt) {
        return tx.execute(status -> {
            if (!sessions.lockOwned(owner, sessionId)) {
                throw new SessionNotFoundException();
            }
            var existing = runs.findByIdempotent(owner.tenantId(), owner.userId(), sessionId, requestKey);
            if (existing.isPresent()) {
                if (!existing.get().requestHash().equals(requestHash)) {
                    throw new BusyException("requestKey 已被不同请求使用");
                }
                return new CreatedRun(existing.get().id(), true);
            }
            var open = runs.findOpenBySession(owner.tenantId(), sessionId);
            if (open.isPresent()) {
                throw new BusyException("该会话已有未结束任务: " + open.get().id());
            }

            UUID runId = UUID.randomUUID();
            sessions.autoTitle(owner, sessionId, userMessage);
            var pinnedConfig=new java.util.LinkedHashMap<>(configSnapshot);
            // The session, rather than a mutable global picker, owns the project for every turn.
            pinnedConfig.remove("projectId");
            pinnedConfig.remove("projectPreviewOrigin");
            UUID projectId=sessions.findById(owner,sessionId).orElseThrow(SessionNotFoundException::new).projectId();
            if(projectId!=null){var project=projects.get(owner,projectId);pinnedConfig.put("projectId",project.id().toString());pinnedConfig.put("projectPreviewOrigin",project.previewOrigin());}
            String configJson = json.write(pinnedConfig);
            String contractJson = json.write(contract);
            runs.insertNew(owner, sessionId, runId, requestKey, requestHash, configJson, contractJson, deadlineAt);

            // user 消息（seq 分配与 INSERT 同事务）
            long seq = sessions.allocateSeq(owner, sessionId);
            messages.insert(new MessageRepo.MessageRow(owner.tenantId(), owner.userId(), sessionId,
                    seq, runId, com.agentops.domain.AgentDomain.MessageRole.USER,
                    json.write(Map.of("content", userMessage)), 1, null, null, Instant.now()));

            // 初始检查点（工作记忆）
            state.insertCheckpoint(owner.tenantId(), owner.userId(), sessionId, runId, 0,
                    json.write(WorkingState.empty(userMessage)));

            // created 事件（seq 递增与事件 INSERT 同事务）
            long eventSeq = runs.nextEventSeqByOwner(owner.tenantId(), runId);
            events.persistAndDispatch(owner.tenantId(), owner.userId(), sessionId, runId,
                    eventSeq, "run.created",
                    Map.of("status", RunStatus.QUEUED.db(), "sessionId", sessionId.toString()));

            // run 级预算账户（T3 预留依赖，文档 17.1）
            budget.initRunAccount(owner, runId);

            // 完成契约 v1
            commands.insertContractRevision(owner.tenantId(), owner.userId(), sessionId, runId,
                    contractJson, "initial", null);

            log.info("T1 run 已创建 run={} session={}", runId, sessionId);
            return new CreatedRun(runId, false);
        });
    }

    // ==================== T2 领取与租约 ====================

    public Optional<Lease> claimReady(UUID workerId, Instant leaseUntil) {
        return runs.claimReady(workerId, leaseUntil).map(this::toLease);
    }

    public Optional<Lease> reclaimExpired(UUID workerId, Instant leaseUntil) {
        return runs.reclaimExpired(workerId, leaseUntil).map(this::toLease);
    }

    private Lease toLease(RunRepo.RunRow row) {
        return new Lease(new RunKey(Principal.of(row.tenantId(), row.userId()), row.sessionId(), row.id()),
                row.workerId(), row.workerEpoch(), row.leaseUntil());
    }

    public RunSnapshot load(RunKey key) {
        var row = runs.findById(key).orElseThrow(() -> new IllegalStateException("run 不存在: " + key.runId()));
        var working = state.findLatest(key.owner().tenantId(), key.runId())
                .map(cp -> json.read(cp.stateJson(), WorkingState.class))
                .orElse(WorkingState.empty(""));
        var contract = json.read(row.completionContractJson(), CompletionContract.class);
        return new RunSnapshot(key, row.status(), row.phase(), row.stateVersion(),
                row.checkpointVersion(), row.workerEpoch(), row.deadlineAt(), row.cancelRequestedAt(),
                contract, working, null,
                row.questionId() == null ? null : row.questionId().toString(), row.questionText(),
                row.repairCount(),
                com.agentops.domain.RunMode.parse(json.tree(row.configSnapshotJson()).path("mode").asText("build")),
                json.tree(row.configSnapshotJson()).path("approvedPlan").asText(""),
                RunRepo.PAUSE_REQUESTED.equals(row.waitingReason()));

    }

    public boolean pauseAtBoundary(Lease lease, RunSnapshot snapshot) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            if (!runs.pauseAtBoundary(lease,snapshot.stateVersion())) return false;
            events.append(snapshot.key().owner(),snapshot.key().sessionId(),snapshot.key().runId(),
                    "run.paused",Map.of("status","paused"));
            return true;
        }));
    }

    public boolean renewLease(Lease lease, long expectedStateVersion, Instant until) {
        return runs.renewLeaseByEpoch(lease, expectedStateVersion, until);
    }

    // ==================== MODEL 阶段 ====================

    /** T3 准备模型调用：预算不足返回 empty（调用方转 BUDGET_EXCEEDED）。 */
    public Optional<AttemptHandle> prepareModelAttempt(Lease lease, RunSnapshot snapshot) {
        Optional<AttemptHandle> prepared = tx.execute(s -> {
            if (snapshot.cancelled() || Instant.now().isAfter(snapshot.deadlineAt())) {
                return Optional.empty();
            }
            var owner = snapshot.key().owner();
            var current=runs.lockByOwner(owner,snapshot.key().runId());
            if(current.isEmpty() || RunRepo.PAUSE_REQUESTED.equals(current.get().waitingReason()))return Optional.empty();
            UUID attemptId = UUID.randomUUID();
            var attempts = attempts(owner, snapshot.key().runId());
            long reserve = 4000L; // 输入估算 + 输出上界（简化估算，可按上下文长度细化）
            if (!budget.reserve(owner, snapshot.key().sessionId(), snapshot.key().runId(), attemptId, attempts, reserve, 0,StepKind.MODEL)) {
                return Optional.empty();
            }
            execution.insertAttempt(owner.tenantId(), owner.userId(), snapshot.key().sessionId(),
                    snapshot.key().runId(), new ExecutionRepo.AttemptRow(attemptId, snapshot.key().runId(),
                            attempts.modelAttempts() + 1, StepKind.MODEL, lease.epoch(),
                            "reserved", null, null, false, reserve, 0));
            if (!runs.advancePhase(lease, snapshot.stateVersion(), Phase.MODEL)) {
                budget.release(owner, snapshot.key().runId(), attemptId, reserve, 0);
                return Optional.empty();
            }
            emit(lease, snapshot, "run.phase", Map.of("phase", "model"));
            return Optional.of(new AttemptHandle(attemptId, attempts.modelAttempts() + 1));
        });
        return prepared == null ? Optional.empty() : prepared;
    }

    /**
     * T4 提交模型决策：完整 assistant 消息（工具批次整体先保存）+ 检查点 +
     * 预算结算 + 事件；推进到下一阶段或等待状态。
     */
    public boolean commitModelDecision(Lease lease, RunSnapshot snapshot, UUID attemptId,
                                       ModelDecision decision, Usage usage, WorkingState working) {
        return commitModelDecision(lease,snapshot,attemptId,decision,usage,working,"");
    }
    public boolean commitModelDecision(Lease lease,RunSnapshot snapshot,UUID attemptId,ModelDecision decision,Usage usage,WorkingState working,String publicCommentary){
        return Boolean.TRUE.equals(tx.execute(s -> {
            var owner = snapshot.key().owner();
            settleModelAttempt(owner, snapshot.key().runId(), attemptId, usage);
            if(!(decision instanceof ModelDecision.ToolBatch)&&publicCommentary!=null&&!publicCommentary.isBlank()){
                long seq=sessions.allocateSeq(owner,snapshot.key().sessionId());
                messages.insert(new MessageRepo.MessageRow(owner.tenantId(),owner.userId(),snapshot.key().sessionId(),seq,snapshot.key().runId(),com.agentops.domain.AgentDomain.MessageRole.ASSISTANT,
                        json.write(Map.of("content",publicCommentary,"kind","commentary","attemptId",attemptId.toString())),1,null,null,Instant.now()));
            }

            if (decision instanceof ModelDecision.ToolBatch batch) {
                long seq = sessions.allocateSeq(owner, snapshot.key().sessionId());
                var calls = new java.util.ArrayList<Map<String, Object>>();
                for (var c : batch.calls()) {
                    calls.add(Map.of("id", c.callId(), "name", c.toolName(),
                            "arguments", c.arguments()));
                }
                messages.insert(new MessageRepo.MessageRow(owner.tenantId(), owner.userId(),
                        snapshot.key().sessionId(), seq, snapshot.key().runId(),
                        com.agentops.domain.AgentDomain.MessageRole.ASSISTANT,
                        json.write(Map.of("content", batch.assistantText() == null ? "" : batch.assistantText(),
                                "toolCalls", calls,"kind","commentary","attemptId",attemptId.toString())),
                        1, (int) usage.totalTokens(), null, Instant.now()));

                var newWorking = working.withToolBatch(json.write(Map.of("calls", calls)),
                        UUID.randomUUID().toString(), seq);
                saveCheckpoint(owner, snapshot, newWorking, lease);
                if (!runs.advancePhase(lease, snapshot.stateVersion(), Phase.TOOLS)) {
                    throw new IllegalStateException("FENCING_ROLLBACK"); // 回滚消息插入
                }
                emit(lease, snapshot, "run.phase", Map.of("phase", "tools"));
                return true;
            }
            if (decision instanceof ModelDecision.FinalCandidate candidate) {
                long seq = sessions.allocateSeq(owner, snapshot.key().sessionId());
                messages.insert(new MessageRepo.MessageRow(owner.tenantId(), owner.userId(),
                        snapshot.key().sessionId(), seq, snapshot.key().runId(),
                        com.agentops.domain.AgentDomain.MessageRole.ASSISTANT,
                        json.write(Map.of("content", candidate.answer().markdown(),
                                "revisionId", candidate.answer().revisionId().toString(),"kind","candidate","attemptId",attemptId.toString())),
                        1, (int) usage.totalTokens(), null, Instant.now()));
                saveCheckpoint(owner, snapshot,
                        working.withProgress(working.completedStepIds(), working.pendingStepIds(), seq), lease);
                if (!runs.advancePhase(lease, snapshot.stateVersion(), Phase.VALIDATE)) {
                    throw new IllegalStateException("FENCING_ROLLBACK"); // 回滚消息插入
                }
                emit(lease, snapshot, "run.phase", Map.of("phase", "validate"));
                return true;
            }
            if (decision instanceof ModelDecision.AskUser ask) {
                saveCheckpoint(owner, snapshot, working, lease);
                runs.saveQuestionOptions(owner,snapshot.key().runId(),ask.questionId(),json.write(ask.options()));
                if(!runs.transitionWaiting(lease, snapshot.stateVersion(), RunStatus.WAITING_USER,
                        "missing_input", ask.questionId(), ask.question()))throw new IllegalStateException("FENCING_ROLLBACK");
                return true;
            }
            return false;
        }));
    }

    private void settleModelAttempt(Principal owner, UUID runId, UUID attemptId, Usage usage) {
        var reserved = budget.reservedTokens(owner.tenantId(), attemptId);
        budget.settle(owner, runId, attemptId, reserved, usage.totalTokens(), usage.finalUsageKnown());
        execution.finishAttempt(owner.tenantId(), runId, attemptId, "succeeded",
                null, null, !usage.finalUsageKnown(), usage.totalTokens(), usage.finalUsageKnown());
        runs.addTokenUsed(owner.tenantId(), runId, usage.totalTokens());
    }

    /** Interrupted generation has no tool effect to reconcile, but may already be billed. */
    public void recordModelInterruption(RunSnapshot snapshot,UUID attemptId){
        recordModelInterruption(snapshot,attemptId,null);
    }
    public void recordModelInterruption(RunSnapshot snapshot,UUID attemptId,Usage actual){
        tx.executeWithoutResult(s->{var owner=snapshot.key().owner();var runId=snapshot.key().runId();
            long reserved=budget.reservedTokens(owner.tenantId(),attemptId);
            boolean known=actual!=null && actual.finalUsageKnown();
            long used=known?actual.totalTokens():reserved;
            if(!execution.interruptModel(owner.tenantId(),runId,attemptId,used))return;
            budget.settle(owner,runId,attemptId,reserved,used,known);
            runs.addTokenUsed(owner.tenantId(),runId,used);
        });
    }
    /** 模型调用失败（T4 变体）：unknown 时保守结算，否则释放预留。 */
    public boolean recordModelFailure(Lease lease, RunSnapshot snapshot, UUID attemptId,
                                      String errorCode, boolean outcomeUnknown, Usage usage) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            var owner = snapshot.key().owner();
            var reserved = budget.reservedTokens(owner.tenantId(), attemptId);
            if (outcomeUnknown) {
                budget.settle(owner, snapshot.key().runId(), attemptId, reserved, reserved, false);
                execution.finishAttempt(owner.tenantId(), snapshot.key().runId(), attemptId,
                        "unknown", null, errorCode, true, 0, false);
            } else {
                budget.release(owner, snapshot.key().runId(), attemptId, reserved, 0);
                execution.finishAttempt(owner.tenantId(), snapshot.key().runId(), attemptId,
                        "failed", null, errorCode, false, 0, false);
            }
            return true;
        }));
    }

    // ==================== TOOLS 阶段 ====================

    /**
     * T3 准备工具调用（文档 16.6 T3）：
     * 版本/取消/授权检查、批准消费、幂等检查、attempt、预算预留。
     */
    public ToolPrepare prepareToolAttempt(Lease lease, RunSnapshot snapshot, ToolIntent intent,
                                          PolicyDecision decision) {
        var owner = snapshot.key().owner();
        // 幂等：effect_key 已登记且已成功（文档 16.2）——不重发，直接采用已有结果
        var prior = execution.findByEffectKey(owner.tenantId(), owner.userId(), intent.effectKey());
        if (prior.isPresent() && Set.of("succeeded", "executing").contains(prior.get().status())) {
            return new ToolPrepare("IDEMPOTENT_REPLAY", null, null);
        }

        var prepared = tx.execute(s -> {
            if (snapshot.cancelled()) return new ToolPrepare("CANCELLED", null, null);
            var current=runs.lockByOwner(owner,snapshot.key().runId());
            if(current.isEmpty())return new ToolPrepare("CANCELLED",null,null);
            if(RunRepo.PAUSE_REQUESTED.equals(current.get().waitingReason()))
                return new ToolPrepare("PAUSE_REQUESTED",null,null);

            if (decision.verdict() == PolicyVerdict.DENY) {
                emit(lease, snapshot, "tool.denied",
                        Map.of("tool", intent.toolName(), "reason", String.valueOf(decision.reasonCode())));
                return new ToolPrepare("DENIED", null, null);
            }

            UUID approvalId = null;
            if (decision.verdict() == PolicyVerdict.REQUIRE_APPROVAL) {
                var pending = approvals.findValidForApproval(owner, snapshot.key().runId(),
                        intent.stepId(), decision.actionDigest());
                if (pending.isEmpty()) {
                    return new ToolPrepare("NEED_APPROVAL", null, null);
                }
                approvalId = pending.get();
            }

            UUID attemptId = UUID.randomUUID();
            var attempts = attempts(owner, snapshot.key().runId());
            if (!budget.reserve(owner, snapshot.key().sessionId(), snapshot.key().runId(), attemptId, attempts, 500, 0,StepKind.TOOL)) {
                return new ToolPrepare("BUDGET_EXCEEDED", null, null);
            }
            execution.insertAttempt(owner.tenantId(), owner.userId(), snapshot.key().sessionId(),
                    snapshot.key().runId(), new ExecutionRepo.AttemptRow(attemptId, intent.stepId(),
                            attempts.toolAttempts() + 1, StepKind.TOOL, lease.epoch(),
                            "reserved", null, null, false, 500, 0));
            execution.updateToolCallStatus(owner.tenantId(), snapshot.key().runId(),
                    intent.modelCallId(), "executing", null, null);

            if (approvalId != null && !approvals.consume(owner, approvalId, decision.actionDigest())) {
                return new ToolPrepare("NEED_APPROVAL", null, null); // 批准被并发消费
            }
            emit(lease, snapshot, "tool.started",
                    Map.of("callId", intent.modelCallId(), "tool", intent.toolName()));
            return new ToolPrepare("OK", approvalId, attemptId);
        });
        return prepared == null ? new ToolPrepare("DENIED", null, null) : prepared;
    }

    private BudgetService.StepAttempts attempts(Principal owner, UUID runId) {
        return new BudgetService.StepAttempts(
                execution.countAttemptsByKind(owner.tenantId(), runId, StepKind.MODEL),
                execution.countAttemptsByKind(owner.tenantId(), runId, StepKind.TOOL));
    }

    /** T4 提交工具结果：结果、预算结算、tool 消息转录、检查点、事件。 */
    public boolean commitToolResult(Lease lease, RunSnapshot snapshot, UUID attemptId,
                                    ToolIntent intent, ToolResult result, Usage usage,
                                    WorkingState working) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            var owner = snapshot.key().owner();
            var runId = snapshot.key().runId();
            var reserved = budget.reservedTokens(owner.tenantId(), attemptId);

            switch (result.status()) {
                case SUCCEEDED -> {
                    budget.settle(owner, runId, attemptId, reserved, usage.totalTokens(), usage.finalUsageKnown());
                    execution.finishAttempt(owner.tenantId(), runId, attemptId, "succeeded",
                            result.providerReference(), null, false, usage.totalTokens(), usage.finalUsageKnown());
                    execution.updateToolCallStatus(owner.tenantId(), runId, intent.modelCallId(),
                            "succeeded", result.artifactId(), null);
                    saveToolMessage(owner, snapshot, intent, result.modelSafeText());
                    saveCheckpoint(owner, snapshot, working, lease);
                    emit(lease, snapshot, "tool.completed", Map.of("callId", intent.modelCallId(),
                            "tool", intent.toolName(), "status", "succeeded"));
                    return true;
                }
                case FAILED -> {
                    budget.settle(owner, runId, attemptId, reserved, 0, true);
                    execution.finishAttempt(owner.tenantId(), runId, attemptId, "failed",
                            null, result.errorCode(), false, 0, true);
                    execution.updateToolCallStatus(owner.tenantId(), runId, intent.modelCallId(),
                            "failed", null, result.errorCode());
                    saveToolMessage(owner, snapshot, intent, result.modelSafeText());
                    saveCheckpoint(owner, snapshot, working, lease);
                    emit(lease, snapshot, "tool.completed", Map.of("callId", intent.modelCallId(),
                            "tool", intent.toolName(), "status", "failed",
                            "error", String.valueOf(result.errorCode())));
                    return true;
                }
                case UNKNOWN -> {
                    // 外部结果未知 ≠ 未执行（文档 1.3.5）：保留预留、进入核对
                    execution.finishAttempt(owner.tenantId(), runId, attemptId, "unknown",
                            result.providerReference(), "OUTCOME_UNKNOWN", true, 0, false);
                    execution.updateToolCallStatus(owner.tenantId(), runId, intent.modelCallId(),
                            "unknown", result.providerReference(), "OUTCOME_UNKNOWN");
                    emit(lease, snapshot, "tool.unknown",
                            Map.of("callId", intent.modelCallId(), "tool", intent.toolName()));
                    return runs.transitionWaiting(lease, snapshot.stateVersion(),
                            RunStatus.WAITING_RECONCILE, "outcome_unknown", null, null);
                }
            }
            return false;
        }));
    }

    private void saveToolMessage(Principal owner, RunSnapshot snapshot, ToolIntent intent, String text) {
        long seq = sessions.allocateSeq(owner, snapshot.key().sessionId());
        messages.insert(new MessageRepo.MessageRow(owner.tenantId(), owner.userId(),
                snapshot.key().sessionId(), seq, snapshot.key().runId(),
                com.agentops.domain.AgentDomain.MessageRole.TOOL,
                json.write(Map.of("content", text == null ? "" : text)),
                1, null, intent.modelCallId(), Instant.now()));
    }

    // ==================== VALIDATE / FINALIZE ====================

    /** T5 成功提交（文档 16.6 T5 / 19.3）：验证未取消/超时 + 未处理命令屏障 + PASS + 事件 + outbox。 */
    public boolean commitSuccess(Lease lease, RunSnapshot snapshot, CandidateAnswer candidate,
                                 List<String> verifiedArtifacts, WorkingState working) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            var owner = snapshot.key().owner();
            var runId = snapshot.key().runId();

            var current = runs.lockByOwner(owner, runId).orElseThrow();
            if (current.cancelRequestedAt() != null) {
                log.info("run={} 已有取消请求，FINALIZE 不提交成功", runId);
                return false;
            }
            if (Instant.now().isAfter(current.deadlineAt())) {
                return false;
            }
            // 文档 29.3：成功事务同时检查没有未处理的关键命令
            var barrier = commands.pendingBarrier(owner.tenantId(), runId);
            if (!barrier.isEmpty()) {
                log.info("run={} 存在未处理关键命令 seq={}，不提交成功", runId, barrier.get(0).commandSeq());
                return false;
            }

            execution.insertValidationReport(owner.tenantId(), owner.userId(), snapshot.key().sessionId(),
                    runId, UUID.randomUUID(), candidate.revisionId(), snapshot.contract().contractVersion(),
                    "contract-validator-1.0", "pass", json.write(List.of()), "",
                    json.write(verifiedArtifacts));

            saveCheckpoint(owner, snapshot,
                    working.withProgress(working.completedStepIds(), List.of(), working.lastMessageSeq()), lease);

            boolean ok = runs.transition(lease, snapshot.stateVersion(), RunStatus.SUCCEEDED,
                    Phase.FINALIZE, null, Instant.now());
            if (ok) {
                long seq = runs.nextEventSeqByOwner(owner.tenantId(), runId);
                events.persistAndDispatch(owner.tenantId(), owner.userId(), snapshot.key().sessionId(),
                        runId, seq, "run.succeeded",
                        Map.of("messageId", candidate.revisionId().toString(),
                                "artifactIds", verifiedArtifacts));
                outbox.insert(owner.tenantId(), owner.userId(), "run-succeeded-" + runId,
                        "memory.extract",
                        json.write(Map.of("runId", runId.toString(),"sessionId",snapshot.key().sessionId().toString())));
            }
            return ok;
        }));
    }

    /** 校验报告落库（FAIL 时）。 */
    public void saveValidationReport(RunSnapshot snapshot, UUID candidateRevision,
                                     String verdict, List<String> errors, String feedback) {
        var owner = snapshot.key().owner();
        execution.insertValidationReport(owner.tenantId(), owner.userId(), snapshot.key().sessionId(),
                snapshot.key().runId(), UUID.randomUUID(), candidateRevision,
                snapshot.contract().contractVersion(), "contract-validator-1.0",
                verdict, json.write(errors), feedback, json.write(List.of()));
    }

    // ==================== 等待、重试与失败 ====================

    public boolean fail(Lease lease, RunSnapshot snapshot, String reasonCode) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            boolean ok = runs.transition(lease, snapshot.stateVersion(), RunStatus.FAILED,
                    snapshot.phase(), reasonCode, Instant.now());
            if (ok) emitTerminal(snapshot, "run.failed", Map.of("reason", String.valueOf(reasonCode)));
            return ok;
        }));
    }

    public boolean retryWait(Lease lease, RunSnapshot snapshot, Instant nextAttemptAt, String reason) {
        boolean ok = runs.transitionRetryWait(lease, snapshot.stateVersion(), nextAttemptAt, reason);
        if (ok) emitTerminal(snapshot, "run.retry_wait", Map.of("reason", String.valueOf(reason)));
        return ok;
    }
    public boolean retryModel(Lease lease,RunSnapshot snapshot,Instant next,String reason,int failures) {
        boolean ok=runs.transitionRetryWait(lease,snapshot.stateVersion(),next,reason);
        if(ok)emitTerminal(snapshot,"run.retry_wait",Map.of("reason",reason,"failures",failures,"limit",3,
                "nextAttemptAt",next.toString()));
        return ok;
    }
    public boolean pauseModelFailures(Lease lease,RunSnapshot snapshot,String reason) {
        return Boolean.TRUE.equals(tx.execute(status->{
            if(!runs.pauseForModelFailure(lease,snapshot.stateVersion(),"MODEL_RETRY_PAUSED_"+reason))return false;
            events.append(snapshot.key().owner(),snapshot.key().sessionId(),snapshot.key().runId(),
                    "run.paused",Map.of("status","paused","reason","MODEL_RETRY_PAUSED_"+reason,"failures",3));
            return true;
        }));
    }

    public boolean budgetExceeded(Lease lease, RunSnapshot snapshot) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            boolean ok = runs.transition(lease, snapshot.stateVersion(), RunStatus.BUDGET_EXCEEDED,
                    snapshot.phase(), "BUDGET_EXCEEDED", Instant.now());
            if (ok) emitTerminal(snapshot, "run.budget_exceeded", Map.of());
            return ok;
        }));
    }

    /** 等待类转 QUEUED（补充输入/批准后重新入队，文档 14.1）。 */
    public boolean wakeToQueued(UUID tenantId, UUID runId, long expectedStateVersion, String reason) {
        var updated = runs.wakeToQueued(tenantId, runId, expectedStateVersion, reason);
        if (updated) {
            var row = runs.findByIdRunOnly(tenantId, runId);
            row.ifPresent(r -> events.persistAndDispatch(tenantId, r.userId(), r.sessionId(), runId,
                    runs.nextEventSeqByOwner(tenantId, runId), "run.queued", Map.of("reason", reason)));
        }
        return updated;
    }

    // ==================== 内部辅助 ====================

    private void saveCheckpoint(Principal owner, RunSnapshot snapshot, WorkingState working, Lease lease) {
        state.insertCheckpoint(owner.tenantId(), owner.userId(), snapshot.key().sessionId(),
                snapshot.key().runId(), lease.epoch(), json.write(working));
        runs.bumpCheckpointVersion(owner.tenantId(), snapshot.key().runId());
    }

    private void emit(Lease lease, RunSnapshot snapshot, String type, Map<String, Object> extra) {
        var owner = snapshot.key().owner();
        long seq = runs.nextEventSeqByOwner(owner.tenantId(), snapshot.key().runId());
        events.persistAndDispatch(owner.tenantId(), owner.userId(), snapshot.key().sessionId(),
                snapshot.key().runId(), seq, type, extra);
    }

    private void emitTerminal(RunSnapshot snapshot, String type, Map<String, Object> extra) {
        var owner = snapshot.key().owner();
        long seq = runs.nextEventSeqByOwner(owner.tenantId(), snapshot.key().runId());
        events.persistAndDispatch(owner.tenantId(), owner.userId(), snapshot.key().sessionId(),
                snapshot.key().runId(), seq, type, extra);
    }

    // ==================== 引擎用阶段推进（fencing 在 RunRepo 内校验） ====================

    /** TOOLS → MODEL（批次完成或无未完成批次时）。 */
    public boolean advancePhaseToModel(Lease lease, RunSnapshot snapshot) {
        boolean ok = runs.advancePhase(lease, snapshot.stateVersion(), Phase.MODEL);
        if (ok) emit(lease, snapshot, "run.phase", Map.of("phase", "model"));
        return ok;
    }

    /** VALIDATE → FINALIZE（校验 PASS 后）。 */
    public boolean advancePhaseToFinalize(Lease lease, RunSnapshot snapshot) {
        boolean ok = runs.advancePhase(lease, snapshot.stateVersion(), Phase.FINALIZE);
        if (ok) emit(lease, snapshot, "run.phase", Map.of("phase", "finalize"));
        return ok;
    }

    /** 保存检查点并回到 MODEL（工具批次完成时）。 */
    public boolean saveCheckpointAndAdvanceToModel(Lease lease, RunSnapshot snapshot,
                                                   WorkingState working) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            saveCheckpoint(snapshot.key().owner(), snapshot, working, lease);
            return runs.advancePhase(lease, snapshot.stateVersion(), Phase.MODEL);
        }));
    }

    /** 增加修复计数（校验 REPAIRABLE 时）。 */
    public boolean increaseRepair(Lease lease, RunSnapshot snapshot) {
        runs.increaseRepairCount(lease, snapshot.stateVersion());
        return true;
    }

    /** 等待批准：登记批准记录后让 run 进入 WAITING_APPROVAL（释放租约）。 */
    public boolean waitingApproval(Lease lease, RunSnapshot snapshot, UUID approvalId, String toolName) {
        var ok = runs.transitionWaiting(lease, snapshot.stateVersion(), RunStatus.WAITING_APPROVAL,
                "approval_required:" + toolName, approvalId, "等待用户批准：" + toolName);
        return ok;
    }

    /** 校验判定 NEED_USER_INPUT 时进入 WAITING_USER（释放租约，等待用户补充输入）。 */
    public boolean waitingUser(Lease lease, RunSnapshot snapshot, UUID questionId, String question) {
        return runs.transitionWaiting(lease, snapshot.stateVersion(), RunStatus.WAITING_USER,
                "need_user_input", questionId, question);
    }

    public boolean waitForProgress(Lease lease,RunSnapshot snapshot){
        return Boolean.TRUE.equals(tx.execute(s->{UUID id=UUID.randomUUID();String question="最近连续多次读取的片段都已读过，尚未新增阶段发现，任务已暂停以避免继续消耗。你希望如何继续？";
            if(!runs.transitionWaiting(lease,snapshot.stateVersion(),RunStatus.WAITING_USER,"no_progress",id,question))return false;
            runs.saveQuestionOptions(snapshot.key().owner(),snapshot.key().runId(),id,json.write(List.of("整理已有发现并结束","继续深入检查（允许重新核验）")));
            emit(lease,snapshot,"run.progress_paused",Map.of("reason","repeated_reads","questionId",id.toString()));return true;
        }));
    }

    /** 取消时存在结果未知的操作：进入核对（保留租约状态，等待 reconcile 命令）。 */
    public boolean waitingReconcileForCancel(Lease lease, RunSnapshot snapshot) {
        return runs.transitionWaiting(lease, snapshot.stateVersion(), RunStatus.WAITING_RECONCILE,
                "cancel_outcome_unknown", null, null);
    }

    /** 无在途操作：直接从 CANCEL_REQUESTED 进入 CANCELLED。 */
    public boolean cancelImmediately(Lease lease, RunSnapshot snapshot) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            // 先登记 CANCEL_REQUESTED（若还未登记），再终止
            var current = runs.findById(snapshot.key()).orElseThrow();
            if (current.cancelRequestedAt() == null) {
                runs.requestCancel(snapshot.key().owner().tenantId(),
                        snapshot.key().owner().userId(), snapshot.key().runId());
            }
            var fresh = runs.findById(snapshot.key()).orElseThrow();
            boolean ok = runs.transition(lease, fresh.stateVersion(), RunStatus.CANCELLED,
                    snapshot.phase(), "USER_CANCELLED", Instant.now());
            if (ok) emitTerminal(snapshot, "run.cancelled", Map.of());
            return ok;
        }));
    }

    // ==================== 供引擎/API 使用的访问器 ====================

    public RunRepo runs() {
        return runs;
    }

    public ExecutionRepo execution() {
        return execution;
    }

    public MessageRepo messages() {
        return messages;
    }

    public StateRepo state() {
        return state;
    }

    public CommandRepo commands() {
        return commands;
    }

    public SessionRepo sessions() {
        return sessions;
    }

    public RunEventService events() {
        return events;
    }

    public Json json() {
        return json;
    }
}
