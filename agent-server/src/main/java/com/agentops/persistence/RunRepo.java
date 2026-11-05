package com.agentops.persistence;

import com.agentops.domain.AgentDomain.Phase;
import com.agentops.domain.AgentDomain.RunStatus;
import com.agentops.domain.Primitives.Lease;
import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.Primitives.RunKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务仓储（文档 8.1 runs / 16 租约与状态 / 16.6 事务边界）。
 *
 * 所有推进类 UPDATE 都在同一语句内校验：
 * owner + worker_id + worker_epoch + state_version + 有效租约 + 未取消。
 * 旧 worker 迟到不能覆盖新进度（文档 16.3 fencing）。
 */
@Repository
public class RunRepo {
    public static final String PAUSE_REQUESTED="user_pause_requested";

    public boolean requestPause(Principal owner, UUID id, long version) {
        return jdbc.update("""
                UPDATE runs SET waiting_reason='user_pause_requested'
                WHERE tenant_id=? AND user_id=? AND id=? AND state_version=? AND status='running'
                  AND cancel_requested_at IS NULL AND deadline_at>now()
                """,owner.tenantId(),owner.userId(),id,version)>0;
    }
    public boolean pauseIdle(Principal owner,UUID id,long version) {
        return jdbc.update("""
                UPDATE runs SET status='paused',waiting_reason=NULL,paused_at=now(),
                  state_version=state_version+1,worker_id=NULL,lease_until=NULL,worker_epoch=worker_epoch+1
                WHERE tenant_id=? AND user_id=? AND id=? AND state_version=? AND status IN ('queued','retry_wait')
                  AND cancel_requested_at IS NULL AND deadline_at>now()
                """,owner.tenantId(),owner.userId(),id,version)>0;
    }
    public boolean pauseAtBoundary(Lease lease,long version) {
        return jdbc.update("""
                UPDATE runs SET status='paused',waiting_reason=NULL,paused_at=now(),
                  state_version=state_version+1,worker_id=NULL,lease_until=NULL,worker_epoch=worker_epoch+1
                WHERE tenant_id=? AND user_id=? AND id=? AND state_version=? AND status='running'
                  AND waiting_reason='user_pause_requested' AND cancel_requested_at IS NULL
                  AND worker_id=? AND worker_epoch=? AND lease_until>now()
                """,lease.run().owner().tenantId(),lease.run().owner().userId(),lease.run().runId(),version,
                lease.workerId(),lease.epoch())>0;
    }
    public boolean resumePaused(Principal owner,UUID id,long version,long checkpoint) {
        return jdbc.update("""
                UPDATE runs SET status='queued',phase='model',waiting_reason=NULL,reason_code=NULL,question_id=NULL,question_text=NULL,
                  deadline_at=deadline_at+(now()-paused_at),paused_at=NULL,checkpoint_version=?,
                  state_version=state_version+1,worker_id=NULL,lease_until=NULL,worker_epoch=worker_epoch+1,next_attempt_at=now()
                WHERE tenant_id=? AND user_id=? AND id=? AND state_version=? AND status='paused'
                  AND paused_at IS NOT NULL AND cancel_requested_at IS NULL
                """,checkpoint,owner.tenantId(),owner.userId(),id,version)>0;
    }
    public boolean pauseForModelFailure(Lease lease,long version,String reason) {
        return jdbc.update("""
                UPDATE runs SET status='paused',reason_code=?,waiting_reason=NULL,paused_at=now(),
                  state_version=state_version+1,worker_id=NULL,lease_until=NULL,worker_epoch=worker_epoch+1
                WHERE tenant_id=? AND user_id=? AND id=? AND state_version=? AND status='running' AND phase='model'
                  AND cancel_requested_at IS NULL AND deadline_at>now()
                  AND worker_id=? AND worker_epoch=? AND lease_until>now()
                """,reason,lease.run().owner().tenantId(),lease.run().owner().userId(),lease.run().runId(),version,
                lease.workerId(),lease.epoch())>0;
    }

    private final JdbcTemplate jdbc;

    public RunRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static boolean isPublicWebTool(String tool) {
        return "web_search".equals(tool) || "web_read".equals(tool) || "skill_preview".equals(tool) || "browser_open".equals(tool)
                || "image_search".equals(tool) || "image_download".equals(tool);
    }
    public static boolean isBrowserInteraction(String tool){return "browser_click".equals(tool)||"browser_fill".equals(tool);}
    public boolean hasSandboxPermission(Principal p,UUID run){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM run_sandbox_permissions WHERE tenant_id=? AND user_id=? AND run_id=?)",Boolean.class,p.tenantId(),p.userId(),run));}
    public void grantSandboxPermission(Principal p,UUID run,UUID approval){jdbc.update("INSERT INTO run_sandbox_permissions(tenant_id,user_id,run_id,approval_id)VALUES(?,?,?,?)ON CONFLICT DO NOTHING",p.tenantId(),p.userId(),run,approval);}
    public boolean hasBrowserPermission(Principal p,UUID run){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM run_browser_permissions WHERE tenant_id=? AND user_id=? AND run_id=?)",Boolean.class,p.tenantId(),p.userId(),run));}
    public void grantBrowserPermission(Principal p,UUID run,UUID approval){jdbc.update("INSERT INTO run_browser_permissions(tenant_id,user_id,run_id,approval_id) VALUES(?,?,?,?) ON CONFLICT(tenant_id,user_id,run_id) DO NOTHING",p.tenantId(),p.userId(),run,approval);}
    public boolean hasNetworkPermission(Principal owner, UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM run_network_permissions
                WHERE tenant_id=? AND user_id=? AND run_id=?)
                """, Boolean.class, owner.tenantId(), owner.userId(), runId));
    }
    public void grantNetworkPermission(Principal owner, UUID runId, UUID approvalId) {
        jdbc.update("""
                INSERT INTO run_network_permissions(tenant_id,user_id,run_id,approval_id)
                VALUES(?,?,?,?) ON CONFLICT(tenant_id,user_id,run_id) DO NOTHING
                """, owner.tenantId(), owner.userId(), runId, approvalId);
    }
    public boolean hasCommandPermission(Principal owner, UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM run_command_permissions
                WHERE tenant_id=? AND user_id=? AND run_id=?)
                """, Boolean.class, owner.tenantId(), owner.userId(), runId));
    }
    public void grantCommandPermission(Principal owner, UUID runId, UUID approvalId) {
        jdbc.update("""
                INSERT INTO run_command_permissions(tenant_id,user_id,run_id,approval_id)
                VALUES(?,?,?,?) ON CONFLICT(tenant_id,user_id,run_id) DO NOTHING
                """, owner.tenantId(), owner.userId(), runId, approvalId);
    }
    public boolean resumeBudgetTask(Principal owner, UUID runId, long version, Instant deadline) {
        return jdbc.update("""
                UPDATE runs SET status='queued', reason_code=NULL, waiting_reason=NULL,
                finished_at=NULL, deadline_at=?, next_attempt_at=now(),
                worker_id=NULL, lease_until=NULL, worker_epoch=worker_epoch+1,
                state_version=state_version+1
                WHERE tenant_id=? AND user_id=? AND id=? AND state_version=?
                  AND status='budget_exceeded' AND cancel_requested_at IS NULL
                """, deadline, owner.tenantId(), owner.userId(), runId, version) > 0;
    }

    public void saveQuestionOptions(Principal owner,UUID runId,UUID questionId,String options) {
        jdbc.update("""
                INSERT INTO run_question_options(tenant_id,user_id,run_id,question_id,options) VALUES(?,?,?,?,?::jsonb)
                ON CONFLICT(tenant_id,user_id,run_id,question_id) DO UPDATE SET options=excluded.options
                """,owner.tenantId(),owner.userId(),runId,questionId,options);
    }
    public List<String> questionOptions(Principal owner,UUID runId,UUID questionId) {
        return jdbc.queryForList("SELECT jsonb_array_elements_text(options) FROM run_question_options WHERE tenant_id=? AND user_id=? AND run_id=? AND question_id=?",
                String.class,owner.tenantId(),owner.userId(),runId,questionId);
    }

    /** runs 行：字段与文档 34 节一致。 */
    public record RunRow(UUID tenantId, UUID userId, UUID sessionId, UUID id,
                         String requestKey, String requestHash,
                         RunStatus status, Phase phase,
                         long stateVersion, long checkpointVersion,
                         UUID workerId, long workerEpoch, Instant leaseUntil,
                         Instant deadlineAt, Instant cancelRequestedAt,
                         Instant nextAttemptAt, long eventSeq, String reasonCode,
                         String waitingReason, UUID questionId, String questionText,
                         String configSnapshotJson, String completionContractJson,
                         int repairCount, int replanCount, long tokenUsed,
                         Instant createdAt, Instant startedAt, Instant finishedAt) {
    }

    private static final String COLS = """
            tenant_id, user_id, session_id, id, request_key, request_hash,
            status, phase, state_version, checkpoint_version, worker_id, worker_epoch,
            lease_until, deadline_at, cancel_requested_at, next_attempt_at, event_seq,
            reason_code, waiting_reason, question_id, question_text,
            config_snapshot::text, completion_contract::text,
            repair_count, replan_count, token_used, created_at, started_at, finished_at
            """;

    private final org.springframework.jdbc.core.RowMapper<RunRow> mapper = Jdbc.reader(RunRepo::mapRunRow);

    private static RunRow mapRunRow(ResultSet rs) throws SQLException {
        return new RunRow(
                Jdbc.uuid(rs, "tenant_id"), Jdbc.uuid(rs, "user_id"), Jdbc.uuid(rs, "session_id"), Jdbc.uuid(rs, "id"),
                rs.getString("request_key"), rs.getString("request_hash"),
                RunStatus.fromDb(rs.getString("status")), Phase.fromDb(rs.getString("phase")),
                rs.getLong("state_version"), rs.getLong("checkpoint_version"),
                Jdbc.uuid(rs, "worker_id"), rs.getLong("worker_epoch"), Jdbc.instant(rs, "lease_until"),
                Jdbc.instant(rs, "deadline_at"), Jdbc.instant(rs, "cancel_requested_at"),
                Jdbc.instant(rs, "next_attempt_at"), rs.getLong("event_seq"),
                rs.getString("reason_code"), rs.getString("waiting_reason"),
                Jdbc.uuid(rs, "question_id"), rs.getString("question_text"),
                rs.getString("config_snapshot"), rs.getString("completion_contract"),
                rs.getInt("repair_count"), rs.getInt("replan_count"), rs.getLong("token_used"),
                Jdbc.instant(rs, "created_at"), Jdbc.instant(rs, "started_at"), Jdbc.instant(rs, "finished_at"));
    }

    // ==================== 创建与查询 ====================

    /** 返回已存在任务（幂等命中）或 null。 */
    public Optional<RunRow> findByIdempotent(UUID tenantId, UUID userId, UUID sessionId, String requestKey) {
        var rows = jdbc.query("""
                SELECT %s FROM runs
                WHERE tenant_id=? AND user_id=? AND session_id=? AND request_key=?
                """.formatted(COLS), mapper, tenantId, userId, sessionId, requestKey);
        return rows.stream().findFirst();
    }

    public List<RunRow> listBySession(Principal owner, UUID sessionId, int limit) {
        return jdbc.query("""
                SELECT %s FROM runs WHERE tenant_id=? AND user_id=? AND session_id=?
                ORDER BY created_at DESC, id DESC LIMIT ?
                """.formatted(COLS), mapper, owner.tenantId(), owner.userId(), sessionId, limit);
    }

    public Optional<RunRow> lockByOwner(Principal owner, UUID runId) {
        return jdbc.query("""
                SELECT %s FROM runs WHERE tenant_id=? AND user_id=? AND id=? FOR UPDATE
                """.formatted(COLS), mapper, owner.tenantId(), owner.userId(), runId)
                .stream().findFirst();
    }

    /** 同会话未结束任务（文档 1.1：同一会话最多一个未结束任务）。 */
    public Optional<RunRow> findOpenBySession(UUID tenantId, UUID sessionId) {
        var rows = jdbc.query("""
                SELECT %s FROM runs
                WHERE tenant_id=? AND session_id=?
                  AND status NOT IN ('succeeded','failed','cancelled','timed_out','budget_exceeded')
                """.formatted(COLS), mapper, tenantId, sessionId);
        return rows.stream().findFirst();
    }

    public boolean hasOpenForOwner(Principal owner) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM runs WHERE tenant_id=? AND user_id=?
                  AND status NOT IN ('succeeded','failed','cancelled','timed_out','budget_exceeded'))
                """, Boolean.class, owner.tenantId(), owner.userId()));
    }

    public record ActiveSessionRun(UUID sessionId, UUID runId, String status) {}

    /** One owner-scoped batch for the visible sidebar; no query per conversation. */
    public java.util.Map<UUID, ActiveSessionRun> activeForSessions(Principal owner, List<UUID> sessionIds) {
        if (sessionIds.isEmpty()) return java.util.Map.of();
        var args = new java.util.ArrayList<Object>(List.of(owner.tenantId(), owner.userId()));
        args.addAll(sessionIds);
        String placeholders = String.join(",", java.util.Collections.nCopies(sessionIds.size(), "?"));
        var rows = jdbc.query("""
                SELECT session_id,id,
                  CASE WHEN cancel_requested_at IS NOT NULL THEN 'cancel_requested'
                       WHEN waiting_reason='user_pause_requested' THEN 'pause_requested' ELSE status END AS status
                FROM runs WHERE tenant_id=? AND user_id=? AND session_id IN (%s)
                  AND status NOT IN ('succeeded','failed','cancelled','timed_out','budget_exceeded')
                """.formatted(placeholders),
                (rs, n) -> new ActiveSessionRun((UUID)rs.getObject("session_id"), (UUID)rs.getObject("id"), rs.getString("status")),
                args.toArray());
        return rows.stream().collect(java.util.stream.Collectors.toMap(ActiveSessionRun::sessionId, r -> r));
    }

    public Optional<RunRow> findById(RunKey key) {
        var rows = jdbc.query("""
                SELECT %s FROM runs WHERE tenant_id=? AND id=?
                """.formatted(COLS), mapper, key.owner().tenantId(), key.runId());
        return rows.stream().findFirst();
    }

    // ==================== T2 领取（调度器受控入口，跨 owner） ====================

    /**
     * 领取一个就绪任务（文档 16.3）：
     * SKIP LOCKED 领取 + epoch 递增 + 设置租约，同一短事务内完成。
     * 注意：不重置 phase——新 run 的 phase 已在 createRun 初始化为 'prepare'；
     * 等待态唤醒（批准/补充输入）的 run 必须保留原 phase（如 tools），引擎才能恢复未完成批次。
     */
    public Optional<RunRow> claimReady(UUID workerId, Instant leaseUntil) {
        var rows = jdbc.query("""
                UPDATE runs
                SET status='running',
                    worker_id=?, worker_epoch=worker_epoch+1,
                    lease_until=?, state_version=state_version+1,
                    started_at=COALESCE(started_at, now())
                WHERE id = (
                    SELECT id FROM runs
                    WHERE status IN ('queued','retry_wait')
                      AND next_attempt_at <= now()
                      AND deadline_at > now()
                      AND cancel_requested_at IS NULL
                    ORDER BY next_attempt_at, created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                RETURNING %s
                """.formatted(COLS), mapper, workerId, leaseUntil);
        return rows.stream().findFirst();
    }

    /** 接管租约过期的 running 任务：新 worker 获得更高 epoch（旧 worker 被 fencing）。 */
    public Optional<RunRow> reclaimExpired(UUID workerId, Instant leaseUntil) {
        var rows = jdbc.query("""
                UPDATE runs
                SET worker_id=?, worker_epoch=worker_epoch+1,
                    lease_until=?, state_version=state_version+1
                WHERE id = (
                    SELECT id FROM runs
                    WHERE status IN ('running','cancel_requested')
                      AND lease_until IS NOT NULL AND lease_until < now()
                      AND deadline_at > now()
                    ORDER BY lease_until
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                RETURNING %s
                """.formatted(COLS), mapper, workerId, leaseUntil);
        return rows.stream().findFirst();
    }

    /** 续租：失败的 worker 必须停止推进（文档 16.3）。 */
    public boolean renewLease(Lease lease, Instant newUntil) {
        return jdbc.update("""
                UPDATE runs SET lease_until=?, state_version=state_version
                WHERE tenant_id=? AND id=? AND worker_id=? AND worker_epoch=?
                  AND state_version=? AND lease_until > now()
                  """, newUntil, lease.run().owner().tenantId(), lease.run().runId(),
                lease.workerId(), lease.epoch(), leaseStateVersion(lease)) > 0;
    }

    /**
     * renewLease 需要期望版本；调用方先 load 快照获得 stateVersion。
     * 简化：租约续期只检查 epoch 与租约有效，不递增 state_version。
     */
    public boolean renewLeaseByEpoch(Lease lease, long expectedStateVersion, Instant newUntil) {
        return jdbc.update("""
                UPDATE runs SET lease_until=?
                WHERE tenant_id=? AND id=? AND worker_id=? AND worker_epoch=?
                  AND state_version=? AND lease_until > now()
                """, newUntil, lease.run().owner().tenantId(), lease.run().runId(),
                lease.workerId(), lease.epoch(), expectedStateVersion) > 0;
    }

    private long leaseStateVersion(Lease lease) {
        return jdbc.queryForObject("""
                SELECT state_version FROM runs WHERE tenant_id=? AND id=?
                """, Long.class, lease.run().owner().tenantId(), lease.run().runId());
    }

    // ==================== 推进（fencing：owner+worker+epoch+version+lease） ====================

    /**
     * 推进执行阶段（PREPARE→MODEL→TOOLS→VALIDATE→FINALIZE）。
     * 注意：阶段推进不递增 state_version——同一 tick 内 T3（prepare）与 T4（commit）
     * 复用同一份快照，若中途递增 version 会导致后续 fencing 比对永远失败。
     * state_version 仅在领取/终态/等待态迁移等改变权威归属的时刻递增。
     */
    public boolean advancePhase(Lease lease, long expectedStateVersion, Phase phase) {
        return jdbc.update("""
                UPDATE runs SET phase=?
                WHERE tenant_id=? AND id=? AND worker_id=? AND worker_epoch=?
                  AND state_version=? AND status='running' AND lease_until > now()
                """, phase.db(), lease.run().owner().tenantId(), lease.run().runId(),
                lease.workerId(), lease.epoch(), expectedStateVersion) > 0;
    }

    /** T4/T5 类：显式状态迁移（含取消与截止时间检查）。 */
    public boolean transition(Lease lease, long expectedStateVersion, RunStatus target,
                              Phase phase, String reasonCode, Instant finishedAt) {
        int updated = jdbc.update("""
                UPDATE runs
                SET status=?, phase=?, reason_code=?,
                    state_version=state_version+1,
                    finished_at=COALESCE(?, finished_at),
                    lease_until=CASE WHEN ? IN ('succeeded','failed','cancelled','timed_out','budget_exceeded')
                                     THEN NULL ELSE lease_until END
                WHERE tenant_id=? AND id=? AND worker_id=? AND worker_epoch=?
                  AND state_version=? AND lease_until > now()
                  AND (status <> 'cancel_requested' OR ? IN ('cancelled','waiting_reconcile','failed'))
                  AND (cancel_requested_at IS NULL OR ? IN ('cancelled','waiting_reconcile','failed'))
                  AND (? <> 'timed_out' OR deadline_at < now() OR cancel_requested_at IS NOT NULL)
                """, target.db(), phase == null ? null : phase.db(), reasonCode,
                finishedAt, target.db(),
                lease.run().owner().tenantId(), lease.run().runId(),
                lease.workerId(), lease.epoch(), expectedStateVersion, target.db(), target.db(), target.db());
        return updated > 0;
    }

    /** 等待类迁移：保留租约为空并释放 worker（文档 14.2 等待时释放线程和租约）。 */
    public boolean transitionWaiting(Lease lease, long expectedStateVersion, RunStatus target,
                                     String waitingReason, UUID questionId, String questionText) {
        return jdbc.update("""
                UPDATE runs
                SET status=?, waiting_reason=?, question_id=?, question_text=?,
                    state_version=state_version+1,
                    worker_id=NULL, lease_until=NULL
                WHERE tenant_id=? AND id=? AND worker_id=? AND worker_epoch=?
                  AND state_version=? AND status='running' AND lease_until > now()
                """, target.db(), waitingReason, questionId, questionText,
                lease.run().owner().tenantId(), lease.run().runId(),
                lease.workerId(), lease.epoch(), expectedStateVersion) > 0;
    }

    /** RETRY_WAIT：安全重试等待（指数退避由调用方计算 nextAttemptAt）。 */
    public boolean transitionRetryWait(Lease lease, long expectedStateVersion,
                                       Instant nextAttemptAt, String reasonCode) {
        return jdbc.update("""
                UPDATE runs
                SET status='retry_wait', next_attempt_at=?, reason_code=?,
                    state_version=state_version+1, worker_id=NULL, lease_until=NULL
                WHERE tenant_id=? AND id=? AND worker_id=? AND worker_epoch=?
                  AND state_version=? AND status='running' AND lease_until > now()
                """, nextAttemptAt, reasonCode,
                lease.run().owner().tenantId(), lease.run().runId(),
                lease.workerId(), lease.epoch(), expectedStateVersion) > 0;
    }

    /** CANCEL_REQUESTED → CANCELLED（无在途操作时可直接终止）。 */
    public boolean finishCancelled(UUID tenantId, UUID runId, long expectedStateVersion) {
        return jdbc.update("""
                UPDATE runs
                SET status='cancelled', finished_at=now(), state_version=state_version+1,
                    worker_id=NULL, lease_until=NULL
                WHERE tenant_id=? AND id=? AND state_version=? AND status='cancel_requested'
                """, tenantId, runId, expectedStateVersion) > 0;
    }

    /** 事件序号原子递增（与事件 INSERT 同事务）。 */
    public long nextEventSeq(Lease lease, long expectedStateVersion) {
        var seq = jdbc.queryForObject("""
                UPDATE runs SET event_seq=event_seq+1
                WHERE tenant_id=? AND id=? AND worker_id=? AND worker_epoch=? AND state_version=?
                RETURNING event_seq
                """, Long.class, lease.run().owner().tenantId(), lease.run().runId(),
                lease.workerId(), lease.epoch(), expectedStateVersion);
        if (seq == null) {
            throw new IllegalStateException("事件序号分配失败：租约或版本校验未通过");
        }
        // 注意：调用方需自行维护 expectedStateVersion 的递增使用或重新加载
        return seq;
    }

    /** 不做 fencing 的事件序号分配（用于 API 侧事件，如命令接收）。 */
    public long nextEventSeqByOwner(UUID tenantId, UUID runId) {
        return jdbc.queryForObject("""
                UPDATE runs SET event_seq=event_seq+1 WHERE tenant_id=? AND id=? RETURNING event_seq
                """, Long.class, tenantId, runId);
    }

    public void bumpCheckpointVersion(UUID tenantId, UUID runId) {
        jdbc.update("""
                UPDATE runs SET checkpoint_version=checkpoint_version+1
                WHERE tenant_id=? AND id=?
                """, tenantId, runId);
    }

    /** 累计 token 用量（预算对账与观测）。 */
    public void addTokenUsed(UUID tenantId, UUID runId, long tokens) {
        jdbc.update("""
                UPDATE runs SET token_used=token_used+? WHERE tenant_id=? AND id=?
                """, tokens, tenantId, runId);
    }

    /** 修复计数递增（不递增 state_version，保持同 tick 内 fencing 一致）。 */
    public void increaseRepairCount(Lease lease, long expectedStateVersion) {
        jdbc.update("""
                UPDATE runs SET repair_count=repair_count+1
                WHERE tenant_id=? AND id=? AND worker_id=? AND worker_epoch=?
                  AND state_version=? AND status='running'
                """, lease.run().owner().tenantId(), lease.run().runId(),
                lease.workerId(), lease.epoch(), expectedStateVersion);
    }

    // ==================== 扫描（文档 16.3 调度器） ====================

    /** 扫描截止时间已到的非终态任务。 */
    public List<RunRow> findExpiredDeadline(int limit) {
        return jdbc.query("""
                SELECT %s FROM runs
                WHERE status NOT IN ('paused','succeeded','failed','cancelled','timed_out','budget_exceeded')
                  AND deadline_at < now()
                ORDER BY deadline_at LIMIT ?
                """.formatted(COLS), mapper, limit);
    }

    /** API 侧：请求取消（持久化 cancel_requested_at，禁止新 attempt）。 */
    public Optional<Instant> requestCancel(UUID tenantId, UUID userId, UUID runId) {
        var rows = jdbc.query("""
                UPDATE runs
                SET cancel_requested_at=COALESCE(cancel_requested_at, now()),
                    state_version=state_version+1,
                    status=CASE WHEN status IN ('queued','retry_wait','waiting_user','waiting_approval','paused')
                                THEN 'cancelled' WHEN status='running' THEN 'cancel_requested' ELSE status END,
                    finished_at=CASE WHEN status IN ('queued','retry_wait','waiting_user','waiting_approval','paused')
                                     THEN now() ELSE finished_at END
                WHERE tenant_id=? AND user_id=? AND id=?
                  AND status NOT IN ('succeeded','failed','cancelled','timed_out','budget_exceeded')
                RETURNING cancel_requested_at
                """, Jdbc.reader(rs -> Jdbc.instant(rs, "cancel_requested_at")),
                tenantId, userId, runId);
        return rows.stream().findFirst();
    }

    /** 截止时间扫描：超时任务置为 timed_out（调度器入口，带 state_version 校验）。 */
    public boolean timeoutRun(UUID tenantId, UUID runId, long expectedStateVersion) {
        return jdbc.update("""
                UPDATE runs
                SET status='timed_out', reason_code='DEADLINE_EXCEEDED', finished_at=now(),
                    state_version=state_version+1, worker_id=NULL, lease_until=NULL
                WHERE tenant_id=? AND id=? AND state_version=?
                  AND status NOT IN ('paused','succeeded','failed','cancelled','timed_out','budget_exceeded')
                  AND deadline_at < now()
                """, tenantId, runId, expectedStateVersion) > 0;
    }

    /** T1 插入 run 行（幂等与占用检查已由调用方完成）。 */
    public void insertNew(Principal owner, UUID sessionId, UUID runId, String requestKey,
                          String requestHash, String configJson, String contractJson, Instant deadlineAt) {
        jdbc.update("""
                INSERT INTO runs (tenant_id, user_id, session_id, id, request_key, request_hash,
                                  status, phase, deadline_at, config_snapshot, completion_contract)
                VALUES (?,?,?,?,?,?, 'queued', 'prepare', ?,?::jsonb,?::jsonb)
                """, owner.tenantId(), owner.userId(), sessionId, runId, requestKey, requestHash,
                deadlineAt, configJson, contractJson);
    }

    /** 等待类状态回到 QUEUED（补充输入/批准后，文档 14.1）。 */
    public boolean wakeToQueued(UUID tenantId, UUID runId, long expectedStateVersion, String reason) {
        return jdbc.update("""
                UPDATE runs
                SET status='queued', waiting_reason=NULL, question_id=NULL, question_text=NULL,
                    reason_code=?, state_version=state_version+1, next_attempt_at=now()
                WHERE tenant_id=? AND id=? AND state_version=?
                  AND status IN ('waiting_user','waiting_approval','waiting_reconcile')
                  AND cancel_requested_at IS NULL AND deadline_at > now()
                """, reason, tenantId, runId, expectedStateVersion) > 0;
    }

    /** 仅按 ID 查询（API 侧回执用，带 owner 过滤）。 */
    public Optional<RunRow> findByIdRunOnly(UUID tenantId, UUID runId) {
        var rows = jdbc.query("""
                SELECT %s FROM runs WHERE tenant_id=? AND id=?
                """.formatted(COLS), mapper, tenantId, runId);
        return rows.stream().findFirst();
    }

    /** API 侧：按 owner + runId 查询（不带 sessionId 约束）。 */
    public Optional<RunRow> findByIdOwner(Principal owner, UUID runId) {
        var rows = jdbc.query("""
                SELECT %s FROM runs WHERE tenant_id=? AND user_id=? AND id=?
                """.formatted(COLS), mapper, owner.tenantId(), owner.userId(), runId);
        return rows.stream().findFirst();
    }

    /** 等待批准被拒绝 → FAILED（文档 14.1：拒绝则失败）。 */
    public boolean transitionToFailed(UUID tenantId, UUID runId, long expectedStateVersion, String reason) {
        return jdbc.update("""
                UPDATE runs
                SET status='failed', reason_code=?, finished_at=now(),
                    state_version=state_version+1, worker_id=NULL, lease_until=NULL
                WHERE tenant_id=? AND id=? AND state_version=?
                  AND status IN ('waiting_approval','waiting_user','waiting_reconcile')
                """, reason, tenantId, runId, expectedStateVersion) > 0;
    }
}
