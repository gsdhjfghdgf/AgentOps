package com.agentops.engine;

import com.agentops.config.AgentProperties;
import com.agentops.coordinator.RunCoordinator;
import com.agentops.domain.AgentDomain.TickResult;
import com.agentops.persistence.RunRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 运行调度器（文档 16.3）：
 * - T2 领取：SKIP LOCKED 原子领取 + epoch 递增 + 设置租约；
 * - 租约过期接管：新 worker 获得更高 epoch（旧 worker 被 fencing）；
 * - 截止时间扫描：超时任务置为 TIMED_OUT/FAILED。
 *
 * 阻塞式模型/工具调用在受控线程池执行，不放事件循环（文档 17.3）。
 */
@Component
public class RunScheduler {

    private static final Logger log = LoggerFactory.getLogger(RunScheduler.class);

    private final RunCoordinator coordinator;
    private final RunRepo runs;
    private final AgentEngine engine;
    private final AgentProperties props;
    private final Executor executor;
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final UUID workerId = UUID.randomUUID();
    private final java.util.concurrent.ScheduledExecutorService heartbeats =
            java.util.concurrent.Executors.newScheduledThreadPool(1, task -> {
                var thread = new Thread(task, "agent-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    @jakarta.annotation.PreDestroy
    void shutdownHeartbeats() { heartbeats.shutdownNow(); }

    public RunScheduler(RunCoordinator coordinator, RunRepo runs, AgentEngine engine,
                        AgentProperties props, @Qualifier("agentExecutor") Executor executor) {
        this.coordinator = coordinator;
        this.runs = runs;
        this.engine = engine;
        this.props = props;
        this.executor = executor;
        log.info("RunScheduler 启动 worker={}", workerId);
    }

    /** 领取就绪任务并异步执行（高频轮询）。 */
    @Scheduled(fixedDelayString = "${agent.scheduler.poll-interval-millis:500}")
    public void pollReady() {
        while (inFlight.get() < props.scheduler().maxConcurrentRuns()) {
            var leaseUntil = Instant.now().plus(Duration.ofSeconds(props.lease().durationSeconds()));
            var claimed = coordinator.claimReady(workerId, leaseUntil);
            if (claimed.isEmpty()) break;
            var lease = claimed.get();
            inFlight.incrementAndGet();
            executor.execute(() -> runSafely(lease));
        }
    }

    /** 接管租约过期的运行中任务（worker 崩溃恢复，文档 16.3）。 */
    @Scheduled(fixedDelayString = "${agent.scheduler.scan-interval-millis:1000}")
    public void reclaimExpired() {
        if (inFlight.get() >= props.scheduler().maxConcurrentRuns()) return;
        var leaseUntil = Instant.now().plus(Duration.ofSeconds(props.lease().durationSeconds()));
        var reclaimed = coordinator.reclaimExpired(workerId, leaseUntil);
        reclaimed.ifPresent(lease -> {
            log.info("接管租约过期任务 run={} 新epoch={}", lease.run().runId(), lease.epoch());
            inFlight.incrementAndGet();
            executor.execute(() -> runSafely(lease));
        });
    }

    /** 截止时间扫描：超时任务终止（文档 14.1 TIMED_OUT）。 */
    @Scheduled(fixedDelay = 5000)
    public void scanDeadlines() {
        var expired = runs.findExpiredDeadline(20);
        for (var row : expired) {
            boolean ok = runs.timeoutRun(row.tenantId(), row.id(), row.stateVersion());
            if (ok) {
                log.info("run={} 超过截止时间，置为 timed_out", row.id());
                var owner = com.agentops.domain.Primitives.Principal.of(row.tenantId(), row.userId());
                long seq = runs.nextEventSeqByOwner(row.tenantId(), row.id());
                coordinator.events().persistAndDispatch(row.tenantId(), row.userId(), row.sessionId(),
                        row.id(), seq, "run.timed_out", java.util.Map.of());
            }
        }
    }

    private void runSafely(com.agentops.domain.Primitives.Lease lease) {
        final var ownedLease = lease;
        long interval = Math.max(500, Math.min(props.lease().renewIntervalMillis(),
                props.lease().durationSeconds() * 1000L / 3));
        var heartbeat = heartbeats.scheduleWithFixedDelay(() -> {
            try {
                var row = runs.findById(ownedLease.run()).orElse(null);
                if (row != null && row.status() == com.agentops.domain.AgentDomain.RunStatus.RUNNING
                        && ownedLease.workerId().equals(row.workerId()) && row.workerEpoch() == ownedLease.epoch()) {
                    coordinator.renewLease(ownedLease, row.stateVersion(),
                            Instant.now().plusSeconds(props.lease().durationSeconds()));
                }
            } catch (RuntimeException e) {
                log.warn("运行租约续期失败 run={}", ownedLease.run().runId());
            }
        }, interval, interval, java.util.concurrent.TimeUnit.MILLISECONDS);
        try {
            // 循环 tick 直到让出（CONTINUE 表示同一 worker 继续推进下一步）
            int hops = 0;
            while (hops++ < 50) { // 单次调度最多 50 步，防止饿死其他任务
                TickResult result = engine.tick(lease);
                if (result != TickResult.CONTINUE) {
                    if (result == TickResult.LEASE_LOST) {
                        log.info("run={} 租约丢失，交还调度", lease.run().runId());
                    }
                    return;
                }
                // CONTINUE：重新加载租约状态（版本已变）
                lease = refreshLease(lease);
                if (lease == null) return;
            }
            log.debug("run={} 单次调度步数达上限，交还调度", lease.run().runId());
        } catch (RuntimeException e) {
            log.error("run={} 执行异常", lease.run().runId(), e);
            // 异常时不主动改状态：租约过期后由接管机制恢复（文档 16.3）
        } finally {
            heartbeat.cancel(false);
            inFlight.decrementAndGet();
        }
    }

    /** CONTINUE 后刷新租约（版本递增了，epoch/worker 不变）。 */
    private com.agentops.domain.Primitives.Lease refreshLease(
            com.agentops.domain.Primitives.Lease lease) {
        var row = runs.findById(lease.run()).orElse(null);
        if (row == null) return null;
        if (row.workerId() == null || !row.workerId().equals(lease.workerId())
                || row.workerEpoch() != lease.epoch()) {
            return null; // 被接管
        }
        if (row.leaseUntil() == null || row.leaseUntil().isBefore(Instant.now())) {
            return null; // 租约已失效
        }
        if (row.status().terminal()) {
            return null;
        }
        return new com.agentops.domain.Primitives.Lease(lease.run(), lease.workerId(),
                lease.epoch(), row.leaseUntil());
    }
}
