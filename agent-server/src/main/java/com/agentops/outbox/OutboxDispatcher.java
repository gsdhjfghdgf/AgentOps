package com.agentops.outbox;

import com.agentops.infra.Json;
import com.agentops.persistence.OutboxRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outbox 派发器（文档 18.3）：
 * 首版直接使用 PG outbox 作为可靠后台任务队列；
 * 领取（短事务）→ 处理 → 完成/退避重试/进入 dead；
 * 至少一次执行要求消费端幂等（按 eventKey 去重）。
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int MAX_ATTEMPTS = 5;

    private final OutboxRepo repo;
    private final Json json;
    private final List<Handler> handlers;
    /** 幂等去重：已处理的 eventKey（内存版；生产应持久化去重表）。 */
    private final ConcurrentHashMap<String, Boolean> processed = new ConcurrentHashMap<>();

    public OutboxDispatcher(OutboxRepo repo, Json json, List<Handler> handlers) {
        this.repo = repo;
        this.json = json;
        this.handlers = handlers;
    }

    /** 消费端处理器。 */
    public interface Handler {
        /** 支持的事件类型。 */
        String type();

        /** 处理（必须幂等：同 payload 重复执行结果一致）。 */
        void handle(UUID tenantId, UUID userId, String eventKey, Map<String, Object> payload);
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatch() {
        repo.requeueExpired();
        var claimToken = UUID.randomUUID();
        var leaseUntil = Instant.now().plus(Duration.ofSeconds(30));
        var batch = repo.claim(10, claimToken, leaseUntil);
        for (var row : batch) {
            // 幂等去重（至少一次语义，文档 18.3）
            if (processed.containsKey(row.eventKey())) {
                repo.markDone(row.id(), claimToken);
                continue;
            }
            var handler = findHandler(row.type());
            if (handler == null) {
                log.warn("outbox 事件无处理器 type={} key={}，标记 dead", row.type(), row.eventKey());
                repo.markDead(row.id(), claimToken, "NO_HANDLER:" + row.type());
                continue;
            }
            try {
                var payload = json.map(row.payloadJson());
                UUID tenantId = row.tenantId();
                UUID userId = row.userId();
                handler.handle(tenantId, userId, row.eventKey(), payload);
                processed.put(row.eventKey(), Boolean.TRUE);
                repo.markDone(row.id(), claimToken);
                log.debug("outbox 处理完成 type={} key={}", row.type(), row.eventKey());
            } catch (RuntimeException e) {
                log.warn("outbox 处理失败 type={} key={} err={}", row.type(), row.eventKey(), e.getMessage());
                if (row.attemptCount() + 1 >= MAX_ATTEMPTS) {
                    repo.markDead(row.id(), claimToken, truncate(e.getMessage()));
                } else {
                    // 指数退避
                    long backoffSeconds = Math.min(300, (long) Math.pow(2, row.attemptCount() + 1));
                    repo.markRetry(row.id(), claimToken, truncate(e.getMessage()),
                            Instant.now().plus(Duration.ofSeconds(backoffSeconds)));
                }
            }
        }
    }

    private Handler findHandler(String type) {
        for (var h : handlers) {
            if (h.type().equals(type)) return h;
        }
        return null;
    }

    private UUID parseUuid(Object o) {
        if (o == null) return null;
        try {
            return UUID.fromString(o.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    /** 已处理计数（观测用）。 */
    public int processedCount() {
        return processed.size();
    }
}
