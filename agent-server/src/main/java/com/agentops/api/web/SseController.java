package com.agentops.api.web;

import com.agentops.config.AgentProperties;
import com.agentops.event.RunEventService;
import com.agentops.infra.Json;
import com.agentops.persistence.RunRepo;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Read committed events in sequence for both replay and live delivery. */
@RestController
public class SseController {
    private final RunRepo runs;
    private final RunEventService events;
    private final AgentProperties props;
    private final Json json;
    private final com.agentops.auth.AuthService auth;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        var thread = new Thread(r, "sse-reader");
        thread.setDaemon(true);
        return thread;
    });

    public SseController(RunRepo runs, RunEventService events, AgentProperties props, Json json) {
        this(runs,events,props,json,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public SseController(RunRepo runs, RunEventService events, AgentProperties props, Json json,com.agentops.auth.AuthService auth) {
        this.runs = runs;
        this.events = events;
        this.props = props;
        this.json = json;
        this.auth=auth;
    }

    @GetMapping(value = "/api/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader("X-User-Id") String user,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") long lastEventSeq,
            HttpServletResponse response) {
        var owner = ApiController.principal(tenant, user);
        var attributes=org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        var login=attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
                ? servlet.getRequest().getAttribute(com.agentops.auth.AuthFilter.ACCOUNT):null;
        UUID loginSession=login instanceof com.agentops.auth.AuthService.Authenticated account?account.sessionId():null;
        long start = cursor(lastEventId, lastEventSeq);
        var initial = runs.findByIdOwner(owner, runId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在"));
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-store");
        var emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        var closed = new AtomicBoolean();
        var job = new AtomicReference<Future<?>>();
        Runnable close = () -> {
            closed.set(true);
            var future = job.get();
            if (future != null) future.cancel(true);
        };
        emitter.onCompletion(close);
        emitter.onTimeout(close);
        emitter.onError(error -> close.run());
        job.set(pool.submit(() -> {
            long after = start;
            long lastHeartbeat = System.nanoTime();
            try {
                long earliest = events.earliestSeq(owner.tenantId(), runId);
                if (after > initial.eventSeq() || (earliest > 0 && after < earliest - 1)) {
                    after = initial.eventSeq();
                    snapshot(emitter, initial, after, true);
                }
                while (!closed.get()) {
                    if(auth!=null&&loginSession!=null&&!auth.active(loginSession))break;
                    var batch = events.replay(owner.tenantId(), runId, after, 100);
                    for (var event : batch) {
                        if (closed.get()) return;
                        var data = new LinkedHashMap<String, Object>(json.map(event.payloadJson()));
                        data.put("runId", runId.toString());
                        data.put("seq", event.seq());
                        data.put("type", event.type());
                        data.put("at", event.createdAt().toString());
                        emitter.send(SseEmitter.event().id(Long.toString(event.seq()))
                                .name(event.type()).data(data));
                        after = event.seq();
                    }
                    if (!batch.isEmpty()) continue;
                    var fresh = runs.findByIdOwner(owner, runId).orElse(null);
                    if (fresh == null) break;
                    if (fresh.status().terminal()) {
                        // The terminal transaction may commit after the preceding event query.
                        if (!events.replay(owner.tenantId(), runId, after, 1).isEmpty()) continue;
                        snapshot(emitter, fresh, after, false);
                        break;
                    }
                    if (System.nanoTime() - lastHeartbeat >=
                            TimeUnit.SECONDS.toNanos(Math.max(1, props.events().heartbeatSeconds()))) {
                        emitter.send(SseEmitter.event().name("heartbeat")
                                .data(Map.of("status", fresh.status().db(), "at", Instant.now().toString())));
                        lastHeartbeat = System.nanoTime();
                    }
                    Thread.sleep(Math.max(50, Math.min(1000, props.events().pollIntervalMillis())));
                }
                emitter.complete();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException | IllegalStateException ex) {
                emitter.complete();
            } catch (RuntimeException ex) {
                emitter.completeWithError(ex);
            }
        }));
        if (closed.get()) job.get().cancel(true);
        return emitter;
    }

    static long cursor(String header, long query) {
        try {
            long value = header == null || header.isBlank() ? query : Long.parseLong(header);
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件游标必须是非负整数");
        }
    }

    private void snapshot(SseEmitter emitter, RunRepo.RunRow row, long seq, boolean reset) throws IOException {
        var data = new LinkedHashMap<String, Object>();
        data.put("runId", row.id().toString());
        data.put("seq", seq);
        data.put("type", "run.snapshot");
        data.put("status", row.status().db());
        data.put("phase", row.phase().db());
        data.put("reset", reset);
        data.put("at", Instant.now().toString());
        emitter.send(SseEmitter.event().id(Long.toString(seq)).name("run.snapshot").data(data));
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
