package com.agentops.api.web;

import com.agentops.api.RunApplicationService;
import com.agentops.api.RunApplicationService.CreateRunRequest;
import com.agentops.api.RunApplicationService.SupplementRequest;
import com.agentops.domain.Primitives.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 会话 / 消息 / 任务 / 命令 REST 接口（文档 21.1）。
 * AuthFilter 校验登录凭据并覆盖 X-Tenant-Id / X-User-Id；这些身份由服务端构造。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final RunApplicationService app;

    public ApiController(RunApplicationService app) {
        this.app = app;
    }

    /** 请求头已由 AuthFilter 替换为登录账号身份，客户端同名请求头不会作为授权依据。 */
    public static Principal principal(String tenantId, String userId) {
        try {
            return Principal.of(UUID.fromString(tenantId), UUID.fromString(userId));
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "X-Tenant-Id / X-User-Id 必须是合法 UUID");
        }
    }

    // ==================== 会话 ====================

    public record CreateSessionRequest(String title, UUID projectId) {
    }

    @PostMapping("/sessions")
    public ResponseEntity<Map<String, Object>> createSession(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @RequestBody CreateSessionRequest req) {
        var owner = principal(tenantId, userId);
        var view = app.createSession(owner, req.title(),req.projectId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "sessionId", view.id(), "title", view.title(),
                "createdAt", view.createdAt().toString(),"projectId",view.projectId()==null?"":view.projectId().toString()));
    }

    @GetMapping("/sessions")
    public Map<String, Object> listSessions(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "false") boolean archived) {
        var owner = principal(tenantId, userId);
        var list = app.searchSessions(owner, limit, query, archived).stream()
                .<Map<String, Object>>map(s -> Map.of(
                        "sessionId", s.id(), "title", s.title(),
                        "updatedAt", s.updatedAt().toString(), "pinned", s.pinned(), "archived", s.archivedAt()!=null,
                        "projectId",s.projectId()==null?"":s.projectId().toString(),
                        "activeRunId",s.activeRunId(), "activeRunStatus",s.activeRunStatus()))
                .toList();
        return Map.of("sessions", list);
    }

    @PutMapping("/sessions/{sessionId}")
    public Map<String,Object> updateSession(@RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader("X-User-Id") String user,@PathVariable UUID sessionId,
            @RequestBody RunApplicationService.SessionUpdate update) {
        var session=app.updateSession(principal(tenant,user),sessionId,update);
        return Map.of("sessionId",session.id(),"title",session.title(),"pinned",session.pinned(),"archived",session.archivedAt()!=null);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public Map<String,Object> deleteSession(@RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader("X-User-Id") String user,@PathVariable UUID sessionId) {
        app.deleteSession(principal(tenant,user),sessionId);
        return Map.of("ok",true);
    }

    @DeleteMapping("/sessions")
    public Map<String,Object> clearSessions(@RequestHeader("X-Tenant-Id") String tenant,
            @RequestHeader("X-User-Id") String user) {
        return Map.of("deleted",app.clearSessionHistory(principal(tenant,user)));
    }

    @GetMapping("/sessions/{sessionId}")
    public Map<String, Object> getSession(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID sessionId) {
        var owner = principal(tenantId, userId);
        var s = app.getSession(owner, sessionId);
        return Map.of("sessionId", s.id(), "title", s.title(),
                "createdAt", s.createdAt().toString(), "updatedAt", s.updatedAt().toString(),
                "projectId",s.projectId()==null?"":s.projectId().toString());
    }

    // ==================== 消息历史（游标分页） ====================

    @GetMapping("/sessions/{sessionId}/messages")
    public Map<String, Object> history(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID sessionId,
            @RequestParam(required = false) Long beforeSeq,
            @RequestParam(defaultValue = "30") int limit) {
        var owner = principal(tenantId, userId);
        var page = app.history(owner, sessionId, beforeSeq, limit);
        var views = page.messages().stream()
                .<Map<String, Object>>map(m -> {
                    var map = new java.util.LinkedHashMap<String, Object>();
                    map.put("seq", m.seq());
                    map.put("role", m.role());
                    map.put("content", m.content());
                    map.put("runId", m.runId());
                    map.put("toolCallId", m.toolCallId());
                    map.put("createdAt", m.createdAt().toString());
                    map.put("toolCalls", m.toolCalls());
                    map.put("kind",m.kind());map.put("attemptId",m.attemptId());
                    return map;
                })
                .toList();
        var result = new java.util.LinkedHashMap<String, Object>();
        result.put("messages", views);
        result.put("nextBeforeSeq", page.nextBeforeSeq());
        result.put("hasMore", page.hasMore());
        return result;
    }

    // ==================== 任务 ====================

    @PostMapping("/sessions/{sessionId}/runs")
    public ResponseEntity<Map<String, Object>> createRun(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID sessionId,
            @RequestBody CreateRunRequest req) {
        var owner = principal(tenantId, userId);
        var resp = app.createRun(owner, sessionId, req);
        var body = Map.<String, Object>of("runId", resp.runId(), "status", resp.status(),
                "idempotentHit", resp.idempotentHit());
        return resp.idempotentHit()
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/sessions/{sessionId}/runs")
    public Map<String, Object> listRuns(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID sessionId,
            @RequestParam(defaultValue = "20") int limit) {
        var owner=principal(tenantId,userId);
        return Map.of("runs", app.listRuns(owner, sessionId, limit)
                .stream().map(view->toRunMap(owner,view)).toList());
    }

    @GetMapping("/runs/{runId}")
    public Map<String, Object> getRunById(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID runId) {
        var owner = principal(tenantId, userId);
        return toRunMap(owner,app.getRun(owner, runId));
    }

    @PostMapping("/runs/{runId}/continue")
    public Map<String,Object> continueTask(@RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,@PathVariable UUID runId) {
        return app.continueBudgetTask(principal(tenantId,userId),runId);
    }

    private Map<String, Object> toRunMap(Principal owner,RunApplicationService.RunView v) {
        return RunResponses.run(v, app.questionOptions(owner, v));
    }

    // ==================== 运行中命令（文档 29） ====================

    @PostMapping("/sessions/{sessionId}/runs/{runId}/supplement")
    public Map<String, Object> supplement(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID sessionId, @PathVariable UUID runId,
            @RequestBody SupplementRequest req) {
        var owner = principal(tenantId, userId);
        return app.supplement(owner, sessionId, runId, req);
    }

    @PostMapping("/runs/{runId}/cancel")
    public Map<String, Object> cancel(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID runId) {
        var owner = principal(tenantId, userId);
        return app.requestCancel(owner, runId);
    }

    public static class ApiException extends RuntimeException {
        private final HttpStatus status;

        public ApiException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }
}
