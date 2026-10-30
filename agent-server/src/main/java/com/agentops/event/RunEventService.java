package com.agentops.event;

import com.agentops.infra.Json;
import com.agentops.persistence.StateRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;
import com.agentops.persistence.RunRepo;
import com.agentops.domain.Primitives.Principal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 运行事件服务（文档 18.2 / 21.2）：
 * 关键事件随状态同事务持久化，每个 run 使用递增 seq；
 * 前端按 run+seq 去重，断线后从游标继续；事件过期则返回权威快照。
 *
 * 这里只负责"已获得 seq 的事件"的落库与分发，seq 分配由调用方在
 * 持有租约的事务内通过 RunRepo.nextEventSeq 完成（保证同事务）。
 */
@Service
public class RunEventService {

    private static final Logger log = LoggerFactory.getLogger(RunEventService.class);

    private final StateRepo repo;
    private final Json json;
    private final RunRepo runs;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();

    public RunEventService(StateRepo repo, Json json, RunRepo runs) {
        this.repo = repo;
        this.json = json;
        this.runs = runs;
    }

    /** 订阅者：SSE 连接的推送端。 */
    @FunctionalInterface
    public interface Subscriber {
        void onEvent(UUID runId, String type, long seq, String payloadJson);
    }

    public void subscribe(Subscriber subscriber) {
        subscribers.add(subscriber);
    }

    public void unsubscribe(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }

    /** 落库 + 分发（调用方已持有租约并在事务内分配了 seq）。 */
    public void persistAndDispatch(UUID tenantId, UUID userId, UUID sessionId, UUID runId,
                                   long seq, String type, Map<String, Object> payload) {
        String payloadJson = json.write(payload);
        repo.insertEvent(tenantId, userId, sessionId, runId, seq, type, payloadJson);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(runId, type, seq, payloadJson);
                }
            });
        } else {
            dispatch(runId, type, seq, payloadJson);
        }
    }

    /** Allocate and insert in one transaction, so a later event cannot overtake a missing row. */
    @Transactional
    public void append(Principal owner, UUID sessionId, UUID runId, String type, Map<String, Object> payload) {
        long seq = runs.nextEventSeqByOwner(owner.tenantId(), runId);
        persistAndDispatch(owner.tenantId(), owner.userId(), sessionId, runId, seq, type, payload);
    }

    /** 仅分发（例如 API 侧命令回执）。 */
    public void dispatch(UUID runId, String type, long seq, String payloadJson) {
        for (var subscriber : subscribers) {
            try {
                subscriber.onEvent(runId, type, seq, payloadJson);
            } catch (RuntimeException e) {
                log.warn("事件订阅者处理失败 type={} seq={} err={}", type, seq, e.getMessage());
            }
        }
    }

    /** SSE 重放（文档 21.2）：afterSeq 之后的事件按 seq 升序返回。 */
    public List<StateRepo.EventRow> replay(UUID tenantId, UUID runId, long afterSeq, int limit) {
        return repo.listAfter(tenantId, runId, afterSeq, limit);
    }

    /** 事件过期兜底：返回权威快照可用起点（首个事件 seq - 1）。 */
    public long earliestSeq(UUID tenantId, UUID runId) {
        return repo.findFirst(tenantId, runId).map(StateRepo.EventRow::seq).orElse(0L);
    }

    /** SSE 事件的统一 data 结构（文档 21.2）。 */
    public Map<String, Object> eventData(UUID runId, long seq, String type, Map<String, Object> extra) {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("runId", runId.toString());
        data.put("seq", seq);
        data.put("type", type);
        if (extra != null) data.putAll(extra);
        data.put("at", Instant.now().toString());
        return data;
    }
}
