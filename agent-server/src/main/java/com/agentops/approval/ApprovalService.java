package com.agentops.approval;

import com.agentops.domain.Primitives.Principal;
import com.agentops.event.RunEventService;
import com.agentops.infra.Json;
import com.agentops.persistence.ApprovalRepo;
import com.agentops.persistence.RunRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.agentops.api.RunApplicationService.ConflictException;
import com.agentops.api.RunApplicationService.NotFoundException;
import com.agentops.api.RunApplicationService.BadRequestException;
import com.agentops.domain.AgentDomain.RunStatus;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 批准服务（文档 15.2）：
 * 批准记录绑定 approver、digest、范围、有效期与消费状态；
 * 参数变化必须重新评估（digest 不匹配则不会命中）。
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalRepo repo;
    private final RunRepo runs;
    private final RunEventService events;
    private final Json json;
    private final Clock clock;

    public ApprovalService(ApprovalRepo repo, RunRepo runs, RunEventService events, Json json, Clock clock) {
        this.repo = repo;
        this.runs = runs;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /** 是否已有有效批准（pending/approved 且未过期）。 */
    public Optional<UUID> findValidForApproval(Principal owner, UUID runId, UUID stepId, String digest) {
        return repo.findValid(owner.tenantId(), runId, stepId, digest, Instant.now(clock))
                .map(ApprovalRepo.ApprovalRow::id);
    }

    /** 消费批准（与创建 attempt 同事务）。 */
    public boolean consume(Principal owner, UUID approvalId, String digest) {
        return repo.consume(owner.tenantId(), approvalId, digest);
    }

    /** 登记待批准操作并让 run 进入 WAITING_APPROVAL（由引擎调用）。 */
    public UUID requestApproval(Principal owner, UUID sessionId, UUID runId, UUID stepId,
                                String callId, String toolName, String digest) {
        var id = repo.insert(owner.tenantId(), owner.userId(), sessionId, runId, stepId,
                callId, toolName, digest, Instant.now(clock).plus(Duration.ofHours(24)));
        long seq = runs.nextEventSeqByOwner(owner.tenantId(), runId);
        events.persistAndDispatch(owner.tenantId(), owner.userId(), sessionId, runId, seq,
                "approval.requested", Map.of(
                        "approvalId", id.toString(),
                        "tool", toolName,
                        "digest", digest,
                        "callId", callId == null ? "" : callId));
        log.info("run={} 等待批准 approval={} tool={} digest={}", runId, id, toolName, digest);
        return id;
    }

    /**
     * 用户批准决策（API 入口）：
     * 校验 owner、pending 状态与有效期；批准后 run 回到 QUEUED 重新执行。
     */
    public record DecisionResult(boolean ok, String reason, String status, UUID runId) {
    }

    @Transactional
    public DecisionResult decide(Principal owner, UUID approvalId, String decision, String reason) {
        if (!"approved".equals(decision) && !"rejected".equals(decision)) {
            throw new BadRequestException("decision 必须为 approved 或 rejected");
        }
        var row = repo.findById(owner.tenantId(), owner.userId(), approvalId)
                .orElse(null);
        if (row == null) {
            return new DecisionResult(false, "批准记录不存在", null, null);
        }
        if (!"pending".equals(row.status())) {
            return new DecisionResult(false, "批准已处理: " + row.status(), row.status(), row.runId());
        }
        var run = runs.lockByOwner(owner, row.runId()).orElseThrow(() -> new NotFoundException("任务不存在"));
        if (run.status() != RunStatus.WAITING_APPROVAL || run.cancelRequestedAt() != null
                || !run.deadlineAt().isAfter(Instant.now(clock))) {
            throw new ConflictException("任务已结束、取消、超时或不再等待批准");
        }
        var decided = repo.decide(owner.tenantId(), owner.userId(), approvalId, decision, reason, Instant.now(clock));
        if (decided.isEmpty()) {
            return new DecisionResult(false, "决策失败（可能已过期或已被处理）", null, null);
        }
        var updated = decided.get();

        // 批准后 run 回到 QUEUED（拒绝则任务失败，文档 14.1）
        boolean applied = "approved".equals(decision)
                ? runs.wakeToQueued(owner.tenantId(), run.id(), run.stateVersion(), "approval_granted")
                : runs.transitionToFailed(owner.tenantId(), run.id(), run.stateVersion(), "approval_rejected");
        if (!applied) throw new ConflictException("任务状态已变化，批准未生效");
        if ("approved".equals(decision) && RunRepo.isPublicWebTool(updated.toolName()))
            runs.grantNetworkPermission(owner, run.id(), approvalId);
        if ("approved".equals(decision) && "command_run".equals(updated.toolName()))
            runs.grantCommandPermission(owner, run.id(), approvalId);
        if ("approved".equals(decision) && "sandbox_run".equals(updated.toolName()))
            runs.grantSandboxPermission(owner,run.id(),approvalId);
        if ("approved".equals(decision) && RunRepo.isBrowserInteraction(updated.toolName()))
            runs.grantBrowserPermission(owner, run.id(), approvalId);
        events.append(owner, run.sessionId(), run.id(), "approval.decided",
                Map.of("approvalId", approvalId.toString(), "decision", decision, "tool", updated.toolName()));
        events.append(owner, run.sessionId(), run.id(), "approved".equals(decision) ? "run.queued" : "run.failed",
                Map.of("reason", "approved".equals(decision) ? "approval_granted" : "approval_rejected"));
        return new DecisionResult(true, null, updated.status(), updated.runId());
    }

    public List<ApprovalRepo.ApprovalRow> listPending(Principal owner, UUID runId) {
        if (runs.findByIdOwner(owner, runId).isEmpty()) throw new NotFoundException("任务不存在");
        return repo.listPending(owner.tenantId(), owner.userId(), runId);
    }

    public Optional<Map<String, Object>> view(Principal owner, UUID approvalId) {
        return repo.findById(owner.tenantId(), owner.userId(), approvalId).map(row -> {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("approvalId", row.id().toString());
            m.put("runId", row.runId().toString());
            m.put("tool", row.toolName());
            m.put("status", row.status());
            m.put("reason", row.reason() == null ? "" : row.reason());
            m.put("expiresAt", row.expiresAt().toString());
            return m;
        });
    }
}
