package com.agentops.api;

import com.agentops.budget.BudgetService;
import com.agentops.config.AgentProperties;
import com.agentops.coordinator.RunCoordinator;
import com.agentops.domain.AgentDomain.RunStatus;
import com.agentops.domain.Primitives.CompletionContract;
import com.agentops.domain.Primitives.Principal;
import com.agentops.infra.Json;
import com.agentops.persistence.MessageRepo;
import com.agentops.persistence.RunRepo;
import com.agentops.persistence.SessionRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 应用服务（API 层入口，文档 21 节接口）：
 * - 会话/消息/任务创建（T1）；
 * - 幂等：客户端 requestKey + 服务端请求体 hash；
 * - 运行中命令：progress_query / supplement / cancel（文档 29）。
 */
@Service
public class RunApplicationService {

    private static final Logger log = LoggerFactory.getLogger(RunApplicationService.class);

    private final SessionRepo sessions;
    private final RunRepo runs;
    private final MessageRepo messages;
    private final RunCoordinator coordinator;
    private final BudgetService budget;
    private final AgentProperties props;
    private final Json json;
    private final com.agentops.model.ModelSettingsService models;
    private final com.agentops.operations.TaskProgressService progress;

    public RunApplicationService(SessionRepo sessions, RunRepo runs, MessageRepo messages,
                                 RunCoordinator coordinator, BudgetService budget,
                                 AgentProperties props, Json json,com.agentops.model.ModelSettingsService models,com.agentops.operations.TaskProgressService progress) {
        this.sessions = sessions;
        this.runs = runs;
        this.messages = messages;
        this.coordinator = coordinator;
        this.budget = budget;
        this.props = props;
        this.json = json; this.models=models;this.progress=progress;
    }

    // ==================== 会话 ====================

    public record SessionView(String id, String title, Instant createdAt, Instant updatedAt,
                              boolean pinned, Instant archivedAt, UUID projectId, String activeRunId, String activeRunStatus) {
        public SessionView(String id, String title, Instant createdAt, Instant updatedAt,
                           boolean pinned, Instant archivedAt, UUID projectId) {
            this(id, title, createdAt, updatedAt, pinned, archivedAt, projectId, "", "");
        }
    }

    public SessionView createSession(Principal owner, String title) {
        return createSession(owner,title,null);
    }

    public SessionView createSession(Principal owner, String title, UUID projectId) {
        var row = projectId==null ? sessions.create(owner, UUID.randomUUID(), title == null ? "" : title)
                : sessions.createProjectSession(owner,UUID.randomUUID(),title,projectId);
        return toSessionView(row);
    }

    public List<SessionView> listSessions(Principal owner, int limit) {
        return sessions.listByOwner(owner, Math.min(Math.max(limit, 1), 100)).stream()
                .map(RunApplicationService::toSessionView).toList();
    }

    public SessionView getSession(Principal owner, UUID sessionId) {
        return sessions.findById(owner, sessionId)
                .map(RunApplicationService::toSessionView)
                .orElseThrow(() -> new NotFoundException("会话不存在"));
    }

    private static SessionView toSessionView(SessionRepo.SessionRow row) {
        return new SessionView(row.id().toString(), row.title(), row.createdAt(), row.updatedAt(),
                row.pinned(), row.archivedAt(),row.projectId());
    }

    public SessionView createEvaluationSession(Principal owner, String title) {
        return toSessionView(sessions.create(owner, UUID.randomUUID(), title, "eval"));
    }

    public List<SessionView> searchSessions(Principal owner, int limit, String query, boolean archived) {
        var rows = sessions.search(owner, Math.min(Math.max(limit,1),100), query == null ? "" : query.strip(), archived);
        var active = runs.activeForSessions(owner, rows.stream().map(SessionRepo.SessionRow::id).toList());
        return rows.stream().map(row -> {
            var task = active.get(row.id());
            return new SessionView(row.id().toString(), row.title(), row.createdAt(), row.updatedAt(),
                    row.pinned(), row.archivedAt(), row.projectId(),
                    task == null ? "" : task.runId().toString(), task == null ? "" : task.status());
        }).toList();
    }

    public record SessionUpdate(String title, Boolean pinned, Boolean archived) {}

    @Transactional
    public SessionView updateSession(Principal owner, UUID id, SessionUpdate update) {
        if (!sessions.lockOwned(owner,id)) throw new NotFoundException("会话不存在");
        if (update.title()!=null) {
            if (update.title().isBlank() || update.title().length()>120) throw new BadRequestException("名称须为 1～120 个字符");
            sessions.rename(owner,id,update.title().strip());
        }
        sessions.organize(owner,id,update.pinned(),update.archived());
        return getSession(owner,id);
    }

    @Transactional
    public void deleteSession(Principal owner, UUID id) {
        if (!sessions.lockOwned(owner,id)) throw new NotFoundException("会话不存在");
        if (runs.findOpenBySession(owner.tenantId(),id).isPresent()) throw new ConflictException("请先停止该会话的任务，再删除会话");
        sessions.softDelete(owner,id);
    }

    @Transactional
    public int clearSessionHistory(Principal owner) {
        sessions.lockHistory(owner);
        if (runs.hasOpenForOwner(owner)) throw new ConflictException("请先停止未结束的任务，再清空会话");
        return sessions.clearHistory(owner);
    }

    // ==================== 消息历史（游标分页，文档 7.1） ====================

    public record MessageView(long seq, String role, String content, String runId,
                              String toolCallId, Instant createdAt, List<Object> toolCalls,String kind,String attemptId) {
    }

    public record MessagePageView(List<MessageView> messages, Long nextBeforeSeq, boolean hasMore) {
    }

    public record MessageDelta(List<MessageView> messages, boolean hasMore) {}

    public MessageDelta messageDelta(Principal owner, UUID sessionId, long afterSeq, int limit) {
        getSession(owner, sessionId);
        if (afterSeq < 0) throw new BadRequestException("afterSeq 不能为负数");
        int size = Math.min(Math.max(limit, 1), 100);
        var rows = messages.listAfter(owner, sessionId, afterSeq, size + 1);
        return new MessageDelta(rows.stream().limit(size).map(this::toMessageView).toList(), rows.size() > size);
    }

    public MessagePageView history(Principal owner, UUID sessionId, Long beforeSeq, int limit) {
        if (sessions.findById(owner, sessionId).isEmpty()) {
            throw new NotFoundException("会话不存在");
        }
        long cursor = beforeSeq == null ? Long.MAX_VALUE : beforeSeq;
        int size = Math.min(Math.max(limit, 1), 100);
        if (cursor < 1) throw new BadRequestException("beforeSeq 必须大于 0");
        var fetched = messages.pageBefore(owner, sessionId, cursor, size + 1);
        boolean hasMore = fetched.size() > size;
        var page = fetched.subList(0, Math.min(size, fetched.size()));
        var views = page.stream().map(p -> toMessageView(p.row()))
                .sorted(java.util.Comparator.comparingLong(MessageView::seq)).toList();
        Long next = page.isEmpty() ? null : page.get(page.size() - 1).row().seq();
        return new MessagePageView(views, next, hasMore);
    }

    private MessageView toMessageView(MessageRepo.MessageRow row) {
        String content = "";
        List<Object> toolCalls = List.of();
        String kind="",attemptId="";
        try {
            var payload = json.tree(row.payloadJson());
            content = payload.path("content").asText("");
            kind=payload.path("kind").asText("");attemptId=payload.path("attemptId").asText("");
            var calls = payload.path("toolCalls");
            if (calls.isArray()) toolCalls = json.list(calls.toString());
        } catch (RuntimeException ignored) {
        }
        return new MessageView(row.seq(), row.role().db(), content,
                row.runId() == null ? null : row.runId().toString(), row.toolCallId(), row.createdAt(), toolCalls,kind,attemptId);
    }

    // ==================== 任务创建（T1） ====================

    public record CreateRunRequest(String requestKey, String message,
                                   String taskType, Map<String, Object> contractRequirements,
                                   List<String> requiredArtifactKinds, Boolean evidenceRequired,
                                   String mode, UUID planRunId) {
        public CreateRunRequest(String requestKey, String message, String taskType,
                                Map<String,Object> requirements, List<String> artifacts, Boolean evidence) {
            this(requestKey, message, taskType, requirements, artifacts, evidence, null, null);
        }
    }

    public record CreateRunResponse(String runId, String status, boolean idempotentHit) {
    }

    public CreateRunResponse createRun(Principal owner, UUID sessionId, CreateRunRequest req) {
        if (req.message() == null || req.message().isBlank()) {
            throw new BadRequestException("message 不能为空");
        }
        com.agentops.domain.RunMode mode;
        try { mode = com.agentops.domain.RunMode.parse(req.mode()); }
        catch (IllegalArgumentException e) { throw new BadRequestException(e.getMessage()); }
        if (req.planRunId() != null && mode != com.agentops.domain.RunMode.BUILD)
            throw new BadRequestException("只有 Build 模式可以接续执行计划");
        String requestKey = req.requestKey() == null || req.requestKey().isBlank()
                ? UUID.randomUUID().toString() : req.requestKey();
        // Hash all effective task inputs, with stable ordering for nested JSON objects.
        var normalized = new CreateRunRequest(null, req.message(),
                req.taskType() == null ? "generic" : req.taskType(),
                req.contractRequirements() == null ? Map.of() : req.contractRequirements(),
                req.requiredArtifactKinds() == null ? List.of() : req.requiredArtifactKinds(),
                Boolean.TRUE.equals(req.evidenceRequired()), mode.value(), req.planRunId());
        String requestHash = sha256(canonical(json.tree(json.write(normalized))));

        // 幂等命中且请求体不同 → 409（文档 16.1）
        var existing = runs.findByIdempotent(owner.tenantId(), owner.userId(), sessionId, requestKey);
        if (existing.isPresent() && !existing.get().requestHash().equals(requestHash)) {
            throw new ConflictException("requestKey 已被不同请求使用");
        }
        if (existing.isPresent()) {
            var row = existing.get();
            return new CreateRunResponse(row.id().toString(), row.status().db(), true);
        }

        String approvedPlan = "";
        if (req.planRunId() != null) {
            var source = runs.findByIdOwner(owner, req.planRunId())
                    .filter(r -> r.sessionId().equals(sessionId))
                    .orElseThrow(() -> new NotFoundException("计划不在当前会话中"));
            if (source.status() != RunStatus.SUCCEEDED
                    || !"plan".equals(json.tree(source.configSnapshotJson()).path("mode").asText()))
                throw new ConflictException("只能执行已完成的 Plan 计划");
            approvedPlan = messages.lastAnswer(owner, sessionId, source.id())
                    .filter(text -> !text.isBlank()).orElseThrow(() -> new ConflictException("计划正文不可用"));
            if (approvedPlan.length() > 24000) throw new BadRequestException("计划过长，请先缩小计划范围");
        }
        var contract = new CompletionContract(1,
                req.taskType() == null ? "generic" : req.taskType(),
                req.contractRequirements() == null ? Map.of() : req.contractRequirements(),
                req.requiredArtifactKinds() == null ? List.of() : req.requiredArtifactKinds(),
                Boolean.TRUE.equals(req.evidenceRequired()), "");
        var modelConfig=models.current(owner);
        var config = new java.util.LinkedHashMap<String, Object>(Map.<String, Object>of(
                "chatProfileId",modelConfig.profileId(),
                "provider", modelConfig.provider(),
                "chatModel", modelConfig.model(),
                "promptVersion", "system-v1",
                "toolRegistryVersion", "1.0.0",
                "policyVersion", "policy-v1",
                "validatorVersion", com.agentops.validation.ResultValidator.version()));
        config.put("mode", mode.value());
        if (req.planRunId() != null) {
            config.put("planRunId", req.planRunId().toString());
            config.put("approvedPlan", approvedPlan);
        }
        var deadline = Instant.now().plus(Duration.ofMinutes(props.budget().runWallTimeoutMinutes()));

        try {
            var created = coordinator.createRun(owner, sessionId, requestKey, requestHash,
                    req.message(), contract, config, deadline);
            return new CreateRunResponse(created.runId().toString(),
                    RunStatus.QUEUED.db(), created.idempotentHit());
        } catch (RunCoordinator.BusyException e) {
            throw new ConflictException(e.getMessage());
        } catch (RunCoordinator.SessionNotFoundException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    // ==================== 任务查询 ====================

    public record RunView(String runId, String sessionId, String status, String phase,
                          String questionId, String questionText, String reasonCode,
                          Instant createdAt, Instant finishedAt, long tokenUsed, String mode, String planRunId) {
    }

    public RunView getRun(Principal owner, UUID runId) {
        var row = runs.findByIdOwner(owner, runId).orElseThrow(() -> new NotFoundException("任务不存在"));
        return toRunView(row);
    }

    public List<RunView> listRuns(Principal owner, UUID sessionId, int limit) {
        getSession(owner, sessionId);
        return runs.listBySession(owner, sessionId, Math.min(Math.max(limit, 1), 100))
                .stream().map(this::toRunView).toList();
    }

    private RunView toRunView(RunRepo.RunRow row) {
        return new RunView(row.id().toString(), row.sessionId().toString(), (RunRepo.PAUSE_REQUESTED.equals(row.waitingReason()) ? "pause_requested" : row.status().db()),
                row.phase().db(),
                row.questionId() == null ? null : row.questionId().toString(),
                row.questionText(), row.reasonCode(), row.createdAt(), row.finishedAt(),
                row.tokenUsed(), json.tree(row.configSnapshotJson()).path("mode").asText("build"),
                json.tree(row.configSnapshotJson()).path("planRunId").asText(""));
    }

    public RunView getRunInSession(Principal owner, UUID sessionId, UUID runId) {
        var row = runs.findByIdOwner(owner, runId)
                .filter(r -> r.sessionId().equals(sessionId))
                .orElseThrow(() -> new NotFoundException("任务不存在"));
        return toRunView(row);
    }

    // ==================== 运行中命令（文档 29） ====================

    /** 补充输入：回答澄清问题后让 WAITING_USER 的任务回到 QUEUED。 */
    public record SupplementRequest(String questionId, String answer) {
    }

    @Transactional
    public Map<String,Object> continueBudgetTask(Principal owner, UUID runId) {
        var owned=runs.findByIdOwner(owner,runId).orElseThrow(()->new NotFoundException("任务不存在"));
        if(!sessions.lockOwned(owner,owned.sessionId())) throw new NotFoundException("会话不存在");
        var row=runs.lockByOwner(owner,runId).orElseThrow(()->new NotFoundException("任务不存在"));
        if(row.status()!=RunStatus.BUDGET_EXCEEDED || row.cancelRequestedAt()!=null)
            throw new ConflictException("只有达到执行额度且未取消的任务可以延长继续");
        if(runs.findOpenBySession(owner.tenantId(),row.sessionId()).isPresent())
            throw new ConflictException("当前会话已有执行中的任务，请先等待或停止该任务");
        var recent=runs.listBySession(owner,row.sessionId(),1);
        if(recent.isEmpty() || !recent.get(0).id().equals(runId))
            throw new ConflictException("该任务之后已有新任务，请在最新任务中继续，避免旧任务混入当前上下文");
        budget.extendRun(owner,runId);
        progress.acknowledge(owner,runId);
        if(!runs.resumeBudgetTask(owner,runId,row.stateVersion(),Instant.now().plus(Duration.ofMinutes(props.budget().runWallTimeoutMinutes()))))
            throw new ConflictException("任务状态已变化，请刷新后重试");
        return Map.of("runId",runId.toString(),"status","queued");
    }
    public List<String> questionOptions(Principal owner,RunView view) {
        return view.questionId()==null?List.of():runs.questionOptions(owner,UUID.fromString(view.runId()),UUID.fromString(view.questionId()));
    }

    @Transactional
    public Map<String, Object> supplement(Principal owner, UUID sessionId, UUID runId,
                                          SupplementRequest req) {
        var row = runs.lockByOwner(owner, runId)
                .filter(r -> r.sessionId().equals(sessionId))
                .orElseThrow(() -> new NotFoundException("任务不存在"));
        if (row.status() != RunStatus.WAITING_USER) {
            throw new BadRequestException("任务不在等待输入状态：" + row.status().db());
        }
        if (req.answer() == null || req.answer().isBlank()) {
            throw new BadRequestException("answer 不能为空");
        }
        if (row.questionId() == null || !row.questionId().toString().equals(req.questionId())) {
            throw new ConflictException("问题已变化，请刷新任务后重新补充");
        }
        if (!coordinator.wakeToQueued(owner.tenantId(), runId, row.stateVersion(), "user_supplemented")) {
            throw new ConflictException("任务状态已变化、取消或超过截止时间");
        }
        progress.acknowledge(owner,runId);

        // 登记命令 + 追加 user 消息 + 状态迁移（同一协调入口）
        var commandId = UUID.randomUUID();
        coordinator.commands().insertCommand(owner.tenantId(), owner.userId(), sessionId, runId,
                commandId, "supplement", json.write(Map.of("questionId",
                        req.questionId() == null ? "" : req.questionId(), "answer", req.answer())),
                row.questionId(), row.stateVersion(), null);
        coordinator.commands().applyCommand(owner.tenantId(), runId, commandId, "applied", Instant.now());

        long seq = sessions.allocateSeq(owner, sessionId);
        messages.insert(new MessageRepo.MessageRow(owner.tenantId(), owner.userId(), sessionId,
                seq, runId, com.agentops.domain.AgentDomain.MessageRole.USER,
                json.write(Map.of("content", req.answer())), 1, null, null, Instant.now()));

        return Map.of("ok", true, "runId", runId.toString(), "seq", seq);
    }

    /** 进度查询（只读，不改变任务状态）。 */
    public Map<String, Object> progress(Principal owner, UUID runId) {
        var view = getRun(owner, runId);
        return Map.of(
                "runId", view.runId(), "status", view.status(), "phase", view.phase(),
                "tokenUsed", view.tokenUsed(),
                "question", view.questionId() == null ? "" : String.valueOf(view.questionText()));
    }

    /** 请求取消（文档 16.5：持久化 cancel_requested_at，禁止新 attempt）。 */
    @Transactional
    public Map<String, Object> requestCancel(Principal owner, UUID runId) {
        var at = runs.requestCancel(owner.tenantId(), owner.userId(), runId)
                .orElseThrow(() -> new BadRequestException("任务已结束或不存在"));
        long seq = runs.nextEventSeqByOwner(owner.tenantId(), runId);
        var row = runs.findByIdOwner(owner, runId).orElseThrow();
        coordinator.events().persistAndDispatch(owner.tenantId(), owner.userId(), row.sessionId(),
                runId, seq, row.status().terminal() ? "run.cancelled" : "run.cancel_requested", Map.of());
        return Map.of("ok", true, "cancelRequestedAt", at.toString());
    }

    // ==================== 异常 ====================

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    private String canonical(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            var sorted = new java.util.TreeMap<String, String>();
            node.fields().forEachRemaining(e -> sorted.put(e.getKey(), canonical(e.getValue())));
            return sorted.entrySet().stream().map(e -> json.write(e.getKey()) + ":" + e.getValue())
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (node.isArray()) {
            var items = new java.util.ArrayList<String>();
            node.forEach(item -> items.add(canonical(item)));
            return "[" + String.join(",", items) + "]";
        }
        return node.toString();
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
