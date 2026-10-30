package com.agentops.engine;

import com.agentops.approval.ApprovalService;
import com.agentops.config.AgentProperties;
import com.agentops.context.ContextAssembler;
import com.agentops.coordinator.RunCoordinator;
import com.agentops.coordinator.RunCoordinator.AttemptHandle;
import com.agentops.domain.AgentDomain.RunStatus;
import com.agentops.domain.AgentDomain.TickResult;
import com.agentops.domain.ModelDecision;
import com.agentops.domain.Primitives.Lease;
import com.agentops.domain.Primitives.RunSnapshot;
import com.agentops.domain.Primitives.WorkingState;
import com.agentops.domain.ResultTypes.CandidateAnswer;
import com.agentops.domain.ResultTypes.Usage;
import com.agentops.domain.ToolTypes.PolicyDecision;
import com.agentops.domain.ToolTypes.ToolCallRequest;
import com.agentops.domain.ToolTypes.ToolIntent;
import com.agentops.event.RunEventService;
import com.agentops.infra.Json;
import com.agentops.model.ModelPort;
import com.agentops.model.ModelPort.ExecutionControl;
import com.agentops.persistence.ExecutionRepo;
import com.agentops.persistence.MessageRepo;
import com.agentops.persistence.RunRepo;
import com.agentops.policy.PolicyService;
import com.agentops.tool.LocalToolExecutor;
import com.agentops.tool.ToolRegistry;
import com.agentops.validation.ResultValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 执行引擎（文档 13.2 / 16 节）：
 *
 * tick = 租约校验 → 按 phase 推进：
 *   PREPARE  —— 恢复点检查（未完成批次、命令屏障、取消/截止）
 *   MODEL    —— 上下文组装 → 模型调用 → 提交决策（T4）
 *   TOOLS    —— 逐个派发（策略→预算→登记→执行→提交结果），批次完成才回模型
 *   VALIDATE —— 三层校验候选答案
 *   FINALIZE —— 原子提交成功（T5）
 *
 * 每个 tick 只推进一步，事务由 RunCoordinator 短事务承载；
 * 网络调用（模型/工具）绝不放在数据库事务内。
 */
@Component
public class AgentEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentEngine.class);

    private final RunCoordinator coordinator;
    private final RunRepo runs;
    private final ExecutionRepo execution;
    private final MessageRepo messages;
    private final ContextAssembler context;
    private final ModelPort model;
    private final PolicyService policy;
    private final ToolRegistry registry;
    private final LocalToolExecutor executor;
    private final ApprovalService approvals;
    private final ResultValidator validator;
    private final RunEventService events;
    private final AgentProperties props;
    private final Json json;
    private final com.agentops.api.SteeringService steering;
    private final com.agentops.persistence.ArtifactRepo artifacts;
    private final com.agentops.artifact.ArtifactService artifactService;
    private final com.agentops.operations.TaskProgressService progress;

    public AgentEngine(RunCoordinator coordinator, RunRepo runs, ExecutionRepo execution,
                       MessageRepo messages, ContextAssembler context, ModelPort model,
                       PolicyService policy, ToolRegistry registry, LocalToolExecutor executor,
                       ApprovalService approvals, ResultValidator validator,
                       RunEventService events, AgentProperties props, Json json,
                       com.agentops.api.SteeringService steering,
                       com.agentops.persistence.ArtifactRepo artifacts,
                       com.agentops.artifact.ArtifactService artifactService,com.agentops.operations.TaskProgressService progress) {
        this.coordinator = coordinator;
        this.runs = runs;
        this.execution = execution;
        this.messages = messages;
        this.context = context;
        this.model = model;
        this.policy = policy;
        this.registry = registry;
        this.executor = executor;
        this.approvals = approvals;
        this.validator = validator;
        this.events = events;
        this.props = props;
        this.json = json;
        this.steering = steering;
        this.artifacts = artifacts;
        this.artifactService = artifactService;
        this.progress=progress;
    }

    /** 执行控制：租约 + 取消 + 截止时间。 */
    private class Control implements ExecutionControl {
        private final Lease lease;
        private final RunSnapshot snapshot;
        private final boolean interruptModel;
        Control(Lease lease,RunSnapshot snapshot) { this(lease,snapshot,false); }
        Control(Lease lease,RunSnapshot snapshot,boolean interruptModel) {
            this.lease=lease;this.snapshot=snapshot;this.interruptModel=interruptModel;
        }
        @Override
        public boolean cancellationRequested() {
            // 读取权威状态中的取消标记（快照可能滞后，但取消只是协作式的）
            return runs.findById(lease.run()).map(row -> row.cancelRequestedAt()!=null
                    || row.status().terminal() || row.workerEpoch()!=lease.epoch()
                    || (interruptModel && RunRepo.PAUSE_REQUESTED.equals(row.waitingReason()))).orElse(true);
        }

        @Override
        public Instant deadlineAt() {
            return snapshot.deadlineAt();
        }
    }

    /**
     * 单次推进：由调度器在 worker 线程调用。返回是否需要继续调度。
     */
    public TickResult tick(Lease lease) {
        RunSnapshot snapshot;
        try {
            snapshot = coordinator.load(lease.run());
        } catch (RuntimeException e) {
            log.warn("加载 run 失败 lease={} err={}", lease.run().runId(), e.getMessage());
            return TickResult.LEASE_LOST;
        }

        // 终态或等待态：不再推进
        if (snapshot.status().terminal() || snapshot.status() == RunStatus.PAUSED || snapshot.status() == RunStatus.WAITING_USER
                || snapshot.status() == RunStatus.WAITING_APPROVAL
                || snapshot.status() == RunStatus.WAITING_RECONCILE) {
            return TickResult.FINISHED;
        }
        // 取消请求：直接进入取消流程（外部副作用可能在途）
        if (snapshot.cancelled()) {
            handleCancelRequested(lease, snapshot);
            return TickResult.FINISHED;
        }
        if (snapshot.pauseRequested()) return pause(lease,snapshot);
        // 截止时间
        if (Instant.now().isAfter(snapshot.deadlineAt())) {
            coordinator.fail(lease, snapshot, "DEADLINE_EXCEEDED");
            return TickResult.FINISHED;
        }
        // 续租
        if (!coordinator.renewLease(lease, snapshot.stateVersion(),
                Instant.now().plus(Duration.ofSeconds(props.lease().durationSeconds())))) {
            log.warn("租约续期失败（可能已被接管）run={}", lease.run().runId());
            return TickResult.LEASE_LOST;
        }

        if (steering.applyAtBoundary(lease,snapshot)) snapshot=coordinator.load(lease.run());
        return switch (snapshot.phase()) {
            case PREPARE -> doPrepare(lease, snapshot);
            case MODEL -> doModel(lease, snapshot);
            case TOOLS -> doTools(lease, snapshot);
            case VALIDATE -> doValidate(lease, snapshot);
            case FINALIZE -> doFinalize(lease, snapshot);
        };
    }

    // ==================== PREPARE ====================

    private TickResult doPrepare(Lease lease, RunSnapshot snapshot) {
        // 命令屏障（文档 29.2）：未处理的 cancel/contract_change 先处理
        var barrier = coordinator.commands().pendingBarrier(
                snapshot.key().owner().tenantId(), snapshot.key().runId());
        for (var cmd : barrier) {
            if ("cancel".equals(cmd.type())) {
                handleCancelRequested(lease, snapshot);
                return TickResult.FINISHED;
            }
            // contract_change：更新快照中的契约后继续（简化：直接应用最新契约）
            log.info("run={} 应用契约变更命令 seq={}", snapshot.key().runId(), cmd.commandSeq());
            coordinator.commands().applyCommandBySeq(snapshot.key().owner().tenantId(),
                    snapshot.key().runId(), cmd.commandSeq(), "applied", Instant.now());
        }

        // 恢复检查：存在 executing 状态的 tool_call 说明上次中断在执行中（文档 16.4）
        var openCalls = execution.listToolCalls(snapshot.key().owner().tenantId(), snapshot.key().runId());
        boolean hasExecuting = openCalls.stream().anyMatch(c -> "executing".equals(c.status()));
        if (hasExecuting) {
            log.info("run={} 检测到中断的执行中工具调用，进入核对", snapshot.key().runId());
            coordinator.retryWait(lease, snapshot,
                    Instant.now().plus(Duration.ofSeconds(5)), "INTERRUPTED_RESUME_CHECK");
            return TickResult.YIELDED;
        }

        // 直接进入 MODEL（每次恢复都重新调用模型决策，文档 13.2）
        return doModel(lease, snapshot);
    }

    // ==================== MODEL ====================

    private TickResult doModel(Lease lease, RunSnapshot snapshot) {
        var owner = snapshot.key().owner();
        if(progress.duplicateStreak(owner,snapshot.key().runId())>=com.agentops.operations.TaskProgressService.PAUSE_AFTER)
            return coordinator.waitForProgress(lease,snapshot)?TickResult.FINISHED:TickResult.LEASE_LOST;

        // T3 准备：预算 + attempt + phase 推进
        var prepared = coordinator.prepareModelAttempt(lease, snapshot);
        if (prepared.isEmpty()) {
            // 预算不足或取消/版本冲突
            if (snapshot.cancelled()) {
                handleCancelRequested(lease, snapshot);
                return TickResult.FINISHED;
            }
            // 再读一次权威状态判断是预算还是冲突
            var fresh = coordinator.load(lease.run());
            if (fresh.pauseRequested()) return pause(lease,fresh);
            if (fresh.status() == RunStatus.RUNNING) {
                coordinator.budgetExceeded(lease, snapshot);
            }
            return TickResult.FINISHED;
        }
        AttemptHandle attempt = prepared.get();

        // 上下文组装（文档 7 节）
        var cursor = coordinator.sessions().currentCursor(owner, snapshot.key().sessionId());
        long lastSeq = cursor.map(c -> c.lastSeq()).orElse(0L);
        String currentRequest = snapshot.workingState() == null ? "" : snapshot.workingState().goal();
        var assembled = context.assemble(owner, snapshot, lastSeq, currentRequest);

        // 可见工具（策略第一道）
        var visible = policy.visibleTools(snapshot);

        var control = new Control(lease, snapshot,true);
        var stream=new ModelStream(snapshot,attempt.attemptId());
        stream.event("message.start",Map.of());

        try {
            var response = model.request(snapshot, assembled.systemPrompt(),
                    assembled.messages(), visible, control,
                    chunk->stream.accept("answer",chunk),chunk->stream.accept("commentary",chunk));

            ModelDecision decision = response.decision();
            if(decision instanceof ModelDecision.ToolBatch batch&&!batch.calls().isEmpty()&&(batch.assistantText()==null||batch.assistantText().isBlank())){
                String intent=PublicProgress.describe(batch.calls());stream.accept("commentary",intent);
                decision=new ModelDecision.ToolBatch(intent,batch.calls());
            }
            stream.flush();
            Usage usage = response.usage() == null
                    ? Usage.unknown(assembled.approxTokens() + stream.answer.length() / 4, 0)
                    : response.usage();

            // 空工具批次防御：模型声称调用工具但没有可执行调用 → 视为失败
            if (decision instanceof ModelDecision.ToolBatch batch && batch.calls().isEmpty()) {
                decision = new ModelDecision.FinalCandidate(new CandidateAnswer(
                        UUID.randomUUID(), batch.assistantText() == null ? "" : batch.assistantText(),
                        List.of(), List.of(), Map.of()));
            }

            var beforeCommit=coordinator.load(lease.run());
            if(beforeCommit.pauseRequested() && beforeCommit.workerEpoch()==lease.epoch()) {
                stream.event("message.interrupted",Map.of("reason","paused"));
                coordinator.recordModelInterruption(beforeCommit,attempt.attemptId(),usage);
                return pause(lease,beforeCommit);
            }
            // T4 提交决策
            boolean committed = coordinator.commitModelDecision(lease, snapshot, attempt.attemptId(),
                    decision, usage, snapshot.workingState(),stream.commentary.toString());
            if (!committed) {
                var latest=coordinator.load(lease.run());
                if (latest.cancelled()) { if(latest.workerEpoch()==lease.epoch())coordinator.recordModelInterruption(latest,attempt.attemptId());handleCancelRequested(lease,latest); return TickResult.FINISHED; }
                log.warn("模型决策提交失败（版本/租约冲突）run={}", snapshot.key().runId());
                return TickResult.LEASE_LOST;
            }
            // 继续下一个 tick（TOOLS 或 VALIDATE 或等待态）
            stream.event("message.committed",Map.of());
            return TickResult.CONTINUE;

        } catch (ModelPort.Cancelled e) {
            var fresh=coordinator.load(lease.run());
            stream.event("message.interrupted",Map.of("reason",fresh.pauseRequested()?"paused":"cancelled"));
            if(fresh.workerEpoch()!=lease.epoch())return TickResult.LEASE_LOST;
            coordinator.recordModelInterruption(fresh,attempt.attemptId());
            if(fresh.cancelled())handleCancelRequested(lease,fresh);
            else if(fresh.pauseRequested())return pause(lease,fresh);
            else if(!fresh.status().terminal())coordinator.fail(lease,fresh,"DEADLINE_EXCEEDED");
            return TickResult.FINISHED;
        } catch (RuntimeException e) {
            stream.event("message.interrupted",Map.of("reason","model_error","code",classifyModelFailure(e)));
            var interrupted=coordinator.load(lease.run());
            if(interrupted.pauseRequested() && interrupted.workerEpoch()==lease.epoch() && !interrupted.cancelled()) {
                coordinator.recordModelInterruption(interrupted,attempt.attemptId());
                return pause(lease,interrupted);
            }
            if ("FENCING_ROLLBACK".equals(e.getMessage())) {
                var latest=coordinator.load(lease.run());
                if (latest.cancelled()) { if(latest.workerEpoch()==lease.epoch())coordinator.recordModelInterruption(latest,attempt.attemptId());handleCancelRequested(lease,latest); return TickResult.FINISHED; }
                // 提交时 fencing 失败 → 事务已回滚（无重复消息），视为租约丢失
                log.warn("模型决策提交失败（版本/租约冲突，已回滚）run={}", snapshot.key().runId());
                return TickResult.LEASE_LOST;
            }
            boolean unknown = isOutcomeUnknown(e);
            String errorCode = classifyModelFailure(e);
            log.warn("模型调用失败 run={} code={} unknown={} cause={}",
                    snapshot.key().runId(), errorCode, unknown,e.getClass().getSimpleName());
            coordinator.recordModelFailure(lease, snapshot, attempt.attemptId(),
                    errorCode, unknown, Usage.unknown(4000, 0));

            // 重试退避（文档 27.2）：可重试则 RETRY_WAIT，否则失败
            if (!unknown && !retryable(e)) {
                coordinator.fail(lease, snapshot, errorCode);
                return TickResult.FINISHED;
            }
            int failures=Math.max(1,execution.consecutiveModelFailures(owner.tenantId(),snapshot.key().runId()));
            if(failures>=3)return coordinator.pauseModelFailures(lease,snapshot,errorCode)?TickResult.FINISHED:TickResult.LEASE_LOST;
            long backoffSeconds=1L<<failures;
            coordinator.retryModel(lease, snapshot,
                    Instant.now().plus(Duration.ofSeconds(backoffSeconds)), errorCode,failures);
            return TickResult.YIELDED;
        }
    }

    /** Batch provider chunks to avoid one database write per token. */
    private final class ModelStream {
        final RunSnapshot snapshot;final UUID attemptId;
        final StringBuilder answer=new StringBuilder(),commentary=new StringBuilder();
        final Map<String,StringBuilder> pending=new LinkedHashMap<>();long lastFlush=System.nanoTime();
        ModelStream(RunSnapshot snapshot,UUID attemptId){this.snapshot=snapshot;this.attemptId=attemptId;}
        void accept(String channel,String text){if(text==null||text.isEmpty())return;
            var whole="answer".equals(channel)?answer:commentary;boolean first=whole.isEmpty();whole.append(text);
            var buffer=pending.computeIfAbsent(channel,k->new StringBuilder());buffer.append(text);
            if(first||buffer.length()>=48||System.nanoTime()-lastFlush>=100_000_000L)flush();
        }
        void flush(){for(var entry:pending.entrySet())if(!entry.getValue().isEmpty()){
            event("message.delta",Map.of("channel",entry.getKey(),"delta",entry.getValue().toString()));entry.getValue().setLength(0);
        }lastFlush=System.nanoTime();}
        void event(String type,Map<String,Object> extra){try{
            var data=new LinkedHashMap<String,Object>(extra);data.put("attemptId",attemptId.toString());
            events.append(snapshot.key().owner(),snapshot.key().sessionId(),snapshot.key().runId(),type,data);
        }catch(RuntimeException e){log.debug("流式事件暂未送达: {}",type);}}
    }

    // ==================== TOOLS ====================

    private TickResult doTools(Lease lease, RunSnapshot snapshot) {
        var owner = snapshot.key().owner();
        var runId = snapshot.key().runId();

        // 从未完成批次恢复（文档 13.2：批次完成前不再次调用模型）
        var batchJson = snapshot.workingState().pendingToolBatchJson();
        List<ToolCallRequest> pendingCalls = parsePendingBatch(batchJson);
        if (pendingCalls.isEmpty()) {
            // 无未完成批次：回模型（让它产生新决策或最终答案）
            if (!coordinator.advancePhaseToModel(lease, snapshot)) {
                return TickResult.LEASE_LOST;
            }
            return TickResult.CONTINUE;
        }

        for (var call : pendingCalls) {
            // 每个工具前重新校验取消/截止/租约（文档 16.5：阻止新行动）
            var fresh = coordinator.load(lease.run());
            if (fresh.cancelled()) {
                handleCancelRequested(lease, fresh);
                return TickResult.FINISHED;
            }
            if(fresh.pauseRequested())return pause(lease,fresh);
            if (Instant.now().isAfter(fresh.deadlineAt())) {
                coordinator.fail(lease, fresh, "DEADLINE_EXCEEDED");
                return TickResult.FINISHED;
            }

            var stepId = UUID.randomUUID();
            var spec = registry.spec(call.toolName());
            if (spec.isEmpty()) {
                // 未注册工具：写入失败结果，继续批次
                recordUnknownTool(lease, fresh, call, stepId);
                continue;
            }

            // 构建意图（step_id/effect_key 在接受完整意图后生成并持久化，文档 16.2）
            var intent = buildIntent(fresh.key().runId(), stepId, call, spec.get());
            PolicyDecision decision = policy.evaluate(owner, fresh, intent);

            // 登记调用意图（执行前持久化，T3 的一部分）
            registerToolCall(owner, fresh, stepId, intent, decision);

            if (decision.verdict() == com.agentops.domain.AgentDomain.PolicyVerdict.DENY) {
                coordinator.prepareToolAttempt(lease, fresh, intent, decision); // 记录 DENIED 事件
                appendToolResultMessage(owner, fresh, call,
                        "工具被策略拒绝：" + decision.reasonCode());
                continue;
            }

            // 需要批准 → 进入 WAITING_APPROVAL
            if (decision.verdict() == com.agentops.domain.AgentDomain.PolicyVerdict.REQUIRE_APPROVAL) {
                var prepare = coordinator.prepareToolAttempt(lease, fresh, intent, decision);
                if ("NEED_APPROVAL".equals(prepare.outcome())) {
                    var approvalId = approvals.requestApproval(owner, fresh.key().sessionId(),
                            runId, stepId, call.callId(), call.toolName(), decision.actionDigest());
                    coordinator.waitingApproval(lease, fresh, approvalId, call.toolName());
                    return TickResult.FINISHED;
                }
                if (!"OK".equals(prepare.outcome())) {
                    log.warn("批准检查未通过 outcome={} run={}", prepare.outcome(), runId);
                    if("BUDGET_EXCEEDED".equals(prepare.outcome())) {
                        coordinator.budgetExceeded(lease,fresh);return TickResult.FINISHED;
                    }
                    if("CANCELLED".equals(prepare.outcome())) {
                        handleCancelRequested(lease,fresh);return TickResult.FINISHED;
                    }
                    if("IDEMPOTENT_REPLAY".equals(prepare.outcome())) {
                        appendToolResultMessage(owner,fresh,call,"该操作已登记执行，不重复执行；请核对已有结果。");continue;
                    }
                    coordinator.fail(lease, fresh, "APPROVAL_CHECK_FAILED");
                    return TickResult.FINISHED;
                }
                // 批准后执行：继续走批次循环（批次完成后统一清空并回模型），
                // 不能直接 return——否则批次不被清空，下一 tick 会重复请求批准形成死循环；
                // 也必须 continue——否则会 fall-through 到下方直接执行段重复 prepare
                var approved = executeTool(lease, fresh, intent, prepare.attemptId());
                if (approved != TickResult.CONTINUE) return approved;
                continue;
            }

            // 直接执行（只读/幂等写）
            var prepare = coordinator.prepareToolAttempt(lease, fresh, intent, decision);
            switch (prepare.outcome()) {
                case "IDEMPOTENT_REPLAY" -> {
                    log.info("工具幂等命中，不重复执行 tool={} effectKey={}",
                            call.toolName(), intent.effectKey());
                    appendToolResultMessage(owner, fresh, call,
                            "（幂等命中：该操作已执行过，未重复执行）");
                }
                case "OK" -> {
                    var result = executeTool(lease, fresh, intent, prepare.attemptId());
                    if (result != TickResult.CONTINUE) return result;
                }
                case "PAUSE_REQUESTED" -> { return pause(lease,coordinator.load(lease.run())); }
                case "BUDGET_EXCEEDED" -> {
                    coordinator.budgetExceeded(lease, fresh);
                    return TickResult.FINISHED;
                }
                case "CANCELLED" -> {
                    handleCancelRequested(lease, fresh);
                    return TickResult.FINISHED;
                }
                default -> {
                    appendToolResultMessage(owner, fresh, call, "工具派发失败：" + prepare.outcome());
                }
            }
        }

        // 批次完成：清空未完成批次，回模型（文档 13.2：批次完成才回模型）
        var cleared = snapshot.workingState().withToolBatch(null, null,
                snapshot.workingState().lastMessageSeq());
        coordinator.saveCheckpointAndAdvanceToModel(lease, snapshot, cleared);
        return TickResult.CONTINUE;
    }

    private TickResult pause(Lease lease,RunSnapshot snapshot) {
        return coordinator.pauseAtBoundary(lease,snapshot)?TickResult.FINISHED:TickResult.LEASE_LOST;
    }

    /** 执行单个工具并提交结果。 */
    private TickResult executeTool(Lease lease, RunSnapshot snapshot, ToolIntent intent, UUID attemptId) {
        var owner = snapshot.key().owner();
        var spec = registry.spec(intent.toolName()).orElseThrow();
        var control = new Control(lease, snapshot);

        var context = new ToolRegistry.ToolContext(snapshot.key().runId(), intent.stepId(),
                intent.effectKey(), control);
        var result = executor.execute(owner, spec, intent.arguments(), context);

        // UNKNOWN → WAITING_RECONCILE（外部结果未知 ≠ 未执行，文档 1.3.5）
        boolean committed = coordinator.commitToolResult(lease, snapshot, attemptId, intent,
                result, Usage.of(0, result.modelSafeText() == null ? 0
                        : result.modelSafeText().length() / 4), snapshot.workingState());
        if (!committed) {
            log.warn("工具结果提交失败（版本/租约冲突）run={} call={}",
                    snapshot.key().runId(), intent.modelCallId());
            return TickResult.LEASE_LOST;
        }
        if (result.status() == com.agentops.domain.AgentDomain.ToolOutcomeStatus.UNKNOWN) {
            return TickResult.FINISHED; // WAITING_RECONCILE
        }
        return TickResult.CONTINUE;
    }

    // ==================== VALIDATE ====================

    private TickResult doValidate(Lease lease, RunSnapshot snapshot) {
        // 读取最新 assistant 消息作为候选答案
        var cursor = coordinator.sessions().currentCursor(snapshot.key().owner(),
                snapshot.key().sessionId());
        long lastSeq = cursor.map(c -> c.lastSeq()).orElse(0L);
        var recent = messages.listRange(snapshot.key().owner().tenantId(),
                snapshot.key().owner().userId(), snapshot.key().sessionId(),
                Math.max(0, lastSeq - 5), lastSeq);
        String candidateText = recent.stream()
                .filter(m -> m.role() == com.agentops.domain.AgentDomain.MessageRole.ASSISTANT)
                .reduce((a, b) -> b) // 最后一条 assistant 消息
                .map(m -> {
                    try {
                        return json.tree(m.payloadJson()).path("content").asText("");
                    } catch (RuntimeException e) {
                        return "";
                    }
                })
                .orElse("");

        String revisionId = recent.stream()
                .filter(m -> m.role() == com.agentops.domain.AgentDomain.MessageRole.ASSISTANT)
                .reduce((a, b) -> b)
                .map(m -> {
                    try {
                        var tree = json.tree(m.payloadJson());
                        return tree.has("revisionId") ? tree.get("revisionId").asText()
                                : UUID.randomUUID().toString();
                    } catch (RuntimeException e) {
                        return UUID.randomUUID().toString();
                    }
                })
                .orElseGet(() -> UUID.randomUUID().toString());

        var verifiedArtifacts=artifacts.listReadyByOwnerRun(snapshot.key().owner().tenantId(),
                snapshot.key().owner().userId(),snapshot.key().runId()).stream()
                .filter(a -> artifactService.resolveForDownload(snapshot.key().owner(),a.objectId()).isPresent())
                .toList();
        var refs=verifiedArtifacts.stream().map(a -> new com.agentops.domain.ResultTypes.ArtifactRef(
                a.objectId().toString(),a.originalName(),a.mimeType(),a.byteSize(),a.sha256())).toList();
        var candidate = new CandidateAnswer(UUID.fromString(revisionId), candidateText,
                List.of(), refs, Map.of());

        // 三层校验（文档 19.3）
        var report = validator.validate(snapshot, candidate);
        if(snapshot.contract()!=null && snapshot.contract().requiredArtifactKinds().stream()
                .anyMatch(kind -> verifiedArtifacts.stream().noneMatch(a -> kind.equals(a.kind())))) {
            report=com.agentops.domain.ResultTypes.ValidationReport.repairable(ResultValidator.version(),
                    List.of("ARTIFACT_REQUIRED_BUT_MISSING"),"缺少任务要求的真实文件产物，请先生成文件。");
        }
        switch (report.verdict()) {
            case PASS -> {
                // T5 原子提交（推进到 FINALIZE，由下个 tick 完成）
                coordinator.saveValidationReport(snapshot, candidate.revisionId(),
                        "pass", List.of(), "");
                if (!coordinator.advancePhaseToFinalize(lease, snapshot)) {
                    return TickResult.LEASE_LOST;
                }
                return TickResult.CONTINUE;
            }
            case REPAIRABLE -> {
                coordinator.saveValidationReport(snapshot, candidate.revisionId(),
                        "repairable", report.errorCodes(), report.safeFeedback());
                int repairs = snapshot.repairCount() + 1;
                if (repairs > 3) {
                    coordinator.fail(lease, snapshot, "REPAIR_LIMIT_EXCEEDED");
                    return TickResult.FINISHED;
                }
                coordinator.increaseRepair(lease, snapshot);
                // 把安全反馈追加为上下文，回模型重试
                appendRepairFeedback(lease, snapshot, report.safeFeedback());
                if (!coordinator.advancePhaseToModel(lease, snapshot)) {
                    return TickResult.LEASE_LOST;
                }
                return TickResult.CONTINUE;
            }
            case NEED_USER_INPUT -> {
                coordinator.saveValidationReport(snapshot, candidate.revisionId(),
                        "need_user_input", report.errorCodes(), report.safeFeedback());
                coordinator.waitingUser(lease, snapshot, UUID.randomUUID(),
                        report.safeFeedback());
                return TickResult.FINISHED;
            }
            case FATAL -> {
                coordinator.saveValidationReport(snapshot, candidate.revisionId(),
                        "fatal", report.errorCodes(), report.safeFeedback());
                coordinator.fail(lease, snapshot, "VALIDATION_FATAL");
                return TickResult.FINISHED;
            }
        }
        return TickResult.FINISHED;
    }

    // ==================== FINALIZE ====================

    private TickResult doFinalize(Lease lease, RunSnapshot snapshot) {
        var cursor = coordinator.sessions().currentCursor(snapshot.key().owner(),
                snapshot.key().sessionId());
        long lastSeq = cursor.map(c -> c.lastSeq()).orElse(0L);
        var recent = messages.listRange(snapshot.key().owner().tenantId(),
                snapshot.key().owner().userId(), snapshot.key().sessionId(),
                Math.max(0, lastSeq - 5), lastSeq);
        String finalText = recent.stream()
                .filter(m -> m.role() == com.agentops.domain.AgentDomain.MessageRole.ASSISTANT)
                .reduce((a, b) -> b)
                .map(m -> {
                    try {
                        return json.tree(m.payloadJson()).path("content").asText("");
                    } catch (RuntimeException e) {
                        return "";
                    }
                })
                .orElse("");
        String revisionId = recent.stream()
                .filter(m -> m.role() == com.agentops.domain.AgentDomain.MessageRole.ASSISTANT)
                .reduce((a, b) -> b)
                .map(m -> {
                    try {
                        var tree = json.tree(m.payloadJson());
                        return tree.has("revisionId") ? tree.get("revisionId").asText()
                                : UUID.randomUUID().toString();
                    } catch (RuntimeException e) {
                        return UUID.randomUUID().toString();
                    }
                })
                .orElseGet(() -> UUID.randomUUID().toString());

        var candidate = new CandidateAnswer(UUID.fromString(revisionId), finalText,
                List.of(), List.of(), Map.of());

        // 收集产物引用
        var artifactIds = new ArrayList<String>();
        for (var call : execution.listToolCalls(snapshot.key().owner().tenantId(), snapshot.key().runId())) {
            if (call.resultRef() != null && !call.resultRef().isBlank()) {
                artifactIds.add(call.resultRef());
            }
        }

        boolean committed = coordinator.commitSuccess(lease, snapshot, candidate,
                artifactIds, snapshot.workingState());
        if (committed) {
            log.info("run={} 成功完成 artifacts={}", snapshot.key().runId(), artifactIds);
            return TickResult.FINISHED;
        }
        // 提交失败：存在取消请求/超时/命令屏障——重新检查状态
        var fresh = coordinator.load(lease.run());
        if (fresh.cancelled()) {
            handleCancelRequested(lease, fresh);
            return TickResult.FINISHED;
        }
        if (Instant.now().isAfter(fresh.deadlineAt())) {
            coordinator.fail(lease, fresh, "DEADLINE_EXCEEDED");
            return TickResult.FINISHED;
        }
        // 命令屏障：等待命令处理完再试
        coordinator.retryWait(lease, fresh, Instant.now().plus(Duration.ofSeconds(2)),
                "FINALIZE_BARRIER_WAIT");
        return TickResult.YIELDED;
    }

    // ==================== 取消与辅助 ====================

    /** 取消请求处理（文档 16.5）：有在途操作 → CANCEL_REQUESTED 等待；无 → 直接 CANCELLED。 */
    private void handleCancelRequested(Lease lease, RunSnapshot snapshot) {
        var owner = snapshot.key().owner();
        var runId = snapshot.key().runId();
        // 检查在途操作（unknown 状态的 attempt 需要核对）
        var openUnknown = execution.listOpenUnknown(owner.tenantId(), runId).stream()
                .filter(a->a.kind()!=com.agentops.domain.AgentDomain.StepKind.MODEL).toList();
        if (!openUnknown.isEmpty()) {
            log.info("run={} 取消时存在结果未知的操作，进入核对", runId);
            coordinator.waitingReconcileForCancel(lease, snapshot);
            return;
        }
        coordinator.cancelImmediately(lease, snapshot);
    }

    private void recordUnknownTool(Lease lease, RunSnapshot snapshot, ToolCallRequest call, UUID stepId) {
        var owner = snapshot.key().owner();
        execution.insertStep(owner.tenantId(), owner.userId(), snapshot.key().sessionId(),
                snapshot.key().runId(), new ExecutionRepo.StepRow(stepId,
                        execution.listSteps(owner.tenantId(), snapshot.key().runId()).size() + 1,
                        com.agentops.domain.AgentDomain.StepKind.TOOL, "failed", null,
                        call.toolName()));
        appendToolResultMessage(owner, snapshot, call, "错误：工具 " + call.toolName() + " 未注册，无法执行。");
    }

    /** 构建工具意图：step_id/effect_key/action_digest（文档 16.2）。 */
    private ToolIntent buildIntent(UUID runId, UUID stepId, ToolCallRequest call,
                                   com.agentops.domain.ToolTypes.ToolSpec spec) {
        String argsHash = policy.argumentsHash(call.arguments() == null ? Map.of() : call.arguments());
        // effect_key = hash(run + tool + args)：幂等作用域限定在单个 run 内。
        // 跨 run 的相同参数调用不应互相干扰（旧 run 残留的 tool_calls 行不应让新 run 误判为幂等命中）。
        // Read-only requests may observe changed files or knowledge. A new model call must read fresh data;
        // recovery of the same persisted call still reuses its result.
        String readCall = spec.effectClass()==com.agentops.domain.AgentDomain.EffectClass.READ_ONLY?"|"+call.callId():"";
        String effectKey = sha256(runId + "|" + spec.name() + "|" + argsHash + readCall);
        var tempIntent = new ToolIntent(stepId, call.callId(), call.toolName(), spec.version(),
                call.arguments() == null ? Map.of() : call.arguments(), argsHash, "", effectKey);
        return tempIntent;
    }

    /** 登记调用意图（T3 一部分：执行前持久化，恢复时保持不变）。 */
    private void registerToolCall(com.agentops.domain.Primitives.Principal owner,
                                  RunSnapshot snapshot, UUID stepId, ToolIntent intent,
                                  PolicyDecision decision) {
        var runId = snapshot.key().runId();
        var existing = execution.findToolCall(owner.tenantId(), runId, intent.modelCallId());
        if (existing.isPresent()) return; // 已登记（恢复场景）
        // 幂等以 effect_key 为准（文档 16.2：模型重试换 call_id 不代表新业务）
        var byEffect = execution.findByEffectKey(owner.tenantId(), owner.userId(), intent.effectKey());
        if (byEffect.isPresent()) {
            // 同一业务意图已登记：若模型换了 call_id（重试/恢复），把行换绑到新 call_id，
            // 否则后续按 call_id 的状态更新命中 0 行，状态永远停在 recorded。
            var prior = byEffect.get();
            if (!prior.callId().equals(intent.modelCallId())) {
                execution.rebindToolCallIds(owner.tenantId(), runId, prior.callId(),
                        intent.modelCallId(), stepId);
            }
            return;
        }

        int ordinal = execution.listSteps(owner.tenantId(), runId).size() + 1;
        execution.insertStep(owner.tenantId(), owner.userId(), snapshot.key().sessionId(), runId,
                new ExecutionRepo.StepRow(stepId, ordinal, com.agentops.domain.AgentDomain.StepKind.TOOL,
                        "running", intent.effectKey(), intent.toolName()));
        execution.insertToolCall(owner.tenantId(), owner.userId(), snapshot.key().sessionId(), runId,
                new ExecutionRepo.ToolCallRow(intent.modelCallId(), stepId, intent.toolName(),
                        intent.toolVersion(), json.write(intent.arguments()), intent.argumentsHash(),
                        decision.actionDigest() == null ? "" : decision.actionDigest(),
                        intent.effectKey(), com.agentops.domain.AgentDomain.EffectClass.READ_ONLY,
                        "recorded", null, null));
    }

    private void appendToolResultMessage(com.agentops.domain.Primitives.Principal owner,
                                         RunSnapshot snapshot, ToolCallRequest call, String text) {
        long seq = coordinator.sessions().allocateSeq(owner, snapshot.key().sessionId());
        coordinator.messages().insert(new MessageRepo.MessageRow(owner.tenantId(), owner.userId(),
                snapshot.key().sessionId(), seq, snapshot.key().runId(),
                com.agentops.domain.AgentDomain.MessageRole.TOOL,
                json.write(Map.of("content", text)), 1, null, call.callId(), Instant.now()));
    }

    private void appendRepairFeedback(Lease lease, RunSnapshot snapshot, String feedback) {
        var owner = snapshot.key().owner();
        long seq = coordinator.sessions().allocateSeq(owner, snapshot.key().sessionId());
        coordinator.messages().insert(new MessageRepo.MessageRow(owner.tenantId(), owner.userId(),
                snapshot.key().sessionId(), seq, snapshot.key().runId(),
                com.agentops.domain.AgentDomain.MessageRole.SYSTEM,
                json.write(Map.of("content", "校验未通过，请修正后重新输出：" + feedback)),
                1, null, null, Instant.now()));
    }

    private List<ToolCallRequest> parsePendingBatch(String batchJson) {
        if (batchJson == null || batchJson.isBlank()) return List.of();
        try {
            var tree = json.tree(batchJson);
            var calls = new ArrayList<ToolCallRequest>();
            for (var node : tree.get("calls")) {
                var argsNode = node.get("arguments");
                Map<String, Object> args = argsNode.isTextual()
                        ? json.map(argsNode.asText()) : json.map(argsNode.toString());
                calls.add(new ToolCallRequest(node.get("id").asText(),
                        node.get("name").asText(), args));
            }
            return calls;
        } catch (RuntimeException e) {
            log.warn("解析未完成工具批次失败: {}", e.getMessage());
            return List.of();
        }
    }

    private boolean isOutcomeUnknown(Throwable e) {
        // 网络异常（超时/中断）：请求可能已到达供应商（文档 27.2）
        for (var c = e; c != null; c = c.getCause()) {
            if (c instanceof java.net.SocketException || c instanceof java.net.SocketTimeoutException
                    || c instanceof org.springframework.web.client.ResourceAccessException) {
                return true;
            }
            if (c.getMessage() != null && c.getMessage().contains("CANCELLED_BEFORE_MODEL")) {
                return false;
            }
        }
        return false;
    }

    private String classifyModelFailure(Throwable e) {
        for(var cause=e;cause!=null;cause=cause.getCause())
            if(cause instanceof com.agentops.model.OpenAiChatClient.ModelUnavailableException m)return m.code();
        if(e instanceof IllegalArgumentException)return "MODEL_INVALID_RESPONSE";
        String msg = e.getMessage() == null ? "" : e.getMessage();
        if (msg.contains("429")) return "MODEL_RATE_LIMITED";
        if (msg.contains("401") || msg.contains("403")) return "MODEL_AUTH_FAILED";
        if (msg.contains("400")) return "MODEL_BAD_REQUEST";
        if (isOutcomeUnknown(e)) return "MODEL_NETWORK_ERROR";
        return "MODEL_UNAVAILABLE";
    }

    private boolean retryable(Throwable e) {
        for (var c = e; c != null; c = c.getCause()) {
            if (c instanceof com.agentops.model.OpenAiChatClient.ModelUnavailableException m) {
                return m.retryable();
            }
        }
        return true; // 网络类默认可重试
    }

    private String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
