package com.agentops.api.web;

import com.agentops.domain.Primitives.Principal;
import com.agentops.eval.EvaluationRunner;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * 评测接口（文档 26 节）：导入套件、运行评测、查看报告。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvaluationRunner runner;

    public EvalController(EvaluationRunner runner) {
        this.runner = runner;
    }

    @PostMapping("/suites/builtin")
    public Map<String, Object> importBuiltin(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId) {
        var owner = ApiController.principal(tenantId, userId);
        return runner.importBuiltinSuite(owner);
    }

    @PostMapping("/suites/{suiteId}/run")
    public Map<String, Object> run(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID suiteId,
            @RequestParam(defaultValue = "60") int timeoutSecondsPerCase) {
        var owner = ApiController.principal(tenantId, userId);
        var result = runner.runSuite(owner, suiteId, timeoutSecondsPerCase);
        return Map.of(
                "suiteId", result.suiteId(), "name", result.name(),
                "totalCases", result.totalCases(), "passedCases", result.passedCases(),
                "avgScore", result.avgScore(), "totalDurationMs", result.totalDurationMs(),
                "trials", result.trials().stream()
                        .<Map<String, Object>>map(t -> Map.of(
                                "taskId", t.taskId(), "score", t.score(),
                                "passed", t.passed(),
                                "errorType", t.errorType() == null ? "" : t.errorType(),
                                "durationMs", t.durationMs() == null ? 0 : t.durationMs()))
                        .toList());
    }

    @GetMapping("/suites/{suiteId}/report")
    public Map<String, Object> report(@RequestHeader("X-Tenant-Id") String tenantId,
                                     @RequestHeader("X-User-Id") String userId,@PathVariable UUID suiteId) {
        return runner.suiteReport(ApiController.principal(tenantId,userId),suiteId);
    }
}
