package com.agentops.eval;

import com.agentops.api.RunApplicationService;
import com.agentops.domain.Primitives.Principal;
import com.agentops.infra.Json;
import com.agentops.persistence.EvalRepo;
import com.agentops.persistence.RunRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 评测运行器（文档 26 节）：
 * 用例 = 输入 + 初始环境 + 允许工具 + 预算 + 完成契约 + 评分标准；
 * 运行 = 串行/并行多次 trial，记录耗时、token、轨迹摘要与分数；
 * 结论 = 规则评分（rule grader），支持回归对比（is_regression）。
 *
 * Mock 模型下可离线跑通全部用例，不需要真实 API Key。
 */
@Service
public class EvaluationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRunner.class);

    private final EvalRepo eval;
    private final RunApplicationService app;
    private final RunRepo runs;
    private final Json json;
    private final com.agentops.model.ModelSettingsService models;
    private final com.agentops.config.AgentProperties props;
    private final com.agentops.persistence.ExecutionRepo execution;
    private final com.agentops.knowledge.KnowledgeService knowledge;

    public EvaluationRunner(EvalRepo eval, RunApplicationService app, RunRepo runs, Json json,
                            com.agentops.config.AgentProperties props,
                            com.agentops.persistence.ExecutionRepo execution,
                            com.agentops.knowledge.KnowledgeService knowledge,com.agentops.model.ModelSettingsService models) {
        this.eval = eval;
        this.app = app;
        this.runs = runs;
        this.json = json; this.props=props; this.execution=execution; this.knowledge=knowledge; this.models=models;
    }

    /** 套件结果汇总。 */
    public record SuiteResult(String suiteId, String name, int totalCases, int passedCases,
                              double avgScore, long totalDurationMs, List<TrialSummaryView> trials) {
    }

    public record TrialSummaryView(String taskId, int trialNo, double score, boolean passed,
                                   String errorType, Long durationMs) {
    }

    /** 导入内置套件（演示：覆盖 6 类用例，文档 26.2）。 */
    public Map<String, Object> importBuiltinSuite(Principal owner) {
        String suiteVersion = "builtin-v2";
        UUID suiteId = eval.insertSuite(owner.tenantId(), "builtin-smoke", suiteVersion,
                "六项冒烟检查：查询、报告、澄清、批准、知识检索和直接回答");

        record CaseDef(String taskId, String category, String input,
                       List<String> allowedTools, Map<String, Object> contract,
                       boolean regression) {
        }
        var cases = List.of(
                new CaseDef("sales_query_normal", "normal",
                        "查询 2026-10-01 到 2026-10-07 的销售数据，用“合计销售额”标注总额。", List.of("sales_query"),
                        Map.of("requiredFacts", List.of("合计销售额")), true),
                new CaseDef("report_generate_normal", "normal",
                        "生成 2026-10-01 到 2026-10-07 的销售周报，输出 Markdown 文件。", List.of("sales_query", "report_generate"),
                        Map.of("requiredArtifactKinds", List.of("output")), true),
                new CaseDef("ambiguous_input", "ambiguous",
                        "帮我查", List.of("sales_query"),
                        Map.of(), false),
                new CaseDef("email_needs_approval", "permission",
                        "给 qa@example.invalid 发送邮件，主题：评测通知，正文：这是批准流程测试。", List.of("send_email"),
                        Map.of(), false),
                new CaseDef("knowledge_rag", "normal",
                        "检索知识库《评测差旅制度》，回答一线城市住宿每晚报销上限是多少，并引用来源。", List.of("knowledge_search"),
                        Map.of("requiredFacts",List.of("600")), false),
                new CaseDef("direct_answer", "normal",
                        "介绍一下你自己", List.of(),
                        Map.of(), false)
        );

        int created = 0;
        for (var c : cases) {
            var existing = eval.listCases(suiteId).stream()
                    .anyMatch(e -> e.taskId().equals(c.taskId()));
            if (!existing) {
                eval.insertCase(suiteId, c.taskId(), c.category(), c.input(),
                        json.write(Map.of()), json.write(c.allowedTools()),
                        json.write(Map.of()), json.write(c.contract()),
                        json.write(Map.of("grader", "rule")), c.regression());
                created++;
            }
        }
        return Map.of("suiteId", suiteId.toString(), "importedCases", created,"name","builtin-smoke","caseCount",cases.size());
    }

    /** 运行整个套件（串行；每个用例 1 次 trial）。 */
    public synchronized SuiteResult runSuite(Principal owner, UUID suiteId, int timeoutSecondsPerCase) {
        requireSuite(owner,suiteId);
        timeoutSecondsPerCase=Math.min(180,Math.max(10,timeoutSecondsPerCase));
        var cases = eval.listCases(suiteId);
        if (cases.isEmpty()) {
            throw new RunApplicationService.BadRequestException("套件无用例");
        }
        String suiteName = eval.findSuiteName(suiteId).orElse("unknown");
        var trials = new ArrayList<TrialSummaryView>();
        int passedCount = 0;
        double scoreSum = 0;
        long durationSum = 0;

        // 每个用例独立会话（隔离环境，文档 26.3）
        for (var c : cases) {
            var trial = runSingleCase(owner, suiteId, c, timeoutSecondsPerCase);
            trials.add(trial);
            if (trial.passed()) passedCount++;
            scoreSum += trial.score();
            durationSum += trial.durationMs() == null ? 0 : trial.durationMs();
        }

        log.info("评测完成 suite={} cases={} passed={} avgScore={}",
                suiteName, cases.size(), passedCount, cases.isEmpty() ? 0 : scoreSum / cases.size());
        return new SuiteResult(suiteId.toString(), suiteName, cases.size(), passedCount,
                cases.isEmpty() ? 0 : scoreSum / cases.size(), durationSum, trials);
    }

    private TrialSummaryView runSingleCase(Principal owner, UUID suiteId, EvalRepo.CaseRow c,
                                           int timeoutSeconds) {
        var session = app.createEvaluationSession(owner, "eval-" + c.taskId());
        UUID sessionId = UUID.fromString(session.id());
        long start = Instant.now().toEpochMilli();
        int trialNo = eval.nextTrialNo(c.id());
        var activeModel=models.current(owner);
        UUID trialId = eval.insertTrial(suiteId, c.id(), trialNo, json.write(Map.of("provider",activeModel.provider(),"chatModel",activeModel.model(),"embeddingModel",props.model().embeddingModel())),
                null, sessionId, "seed-1");

        UUID fixtureId=null;
        String errorType = null;
        UUID runId = null;
        String finalStatus = "unknown";
        try {
            String input=c.input();
            if("knowledge_rag".equals(c.taskId())) {
                String title="评测差旅制度-"+UUID.randomUUID();
                input=input.replace("评测差旅制度",title);
                var fixture=knowledge.importDocument(owner,title,"一线城市住宿报销上限为每晚 600 元；二线城市为 400 元。","user");
                fixtureId=UUID.fromString(fixture.get("documentId").toString());
            }
            var contractReq = new java.util.LinkedHashMap<>(json.map(c.contractJson()));
            contractReq.put("allowedTools",json.list(c.allowedToolsJson()));
            var req = new RunApplicationService.CreateRunRequest(
                    "eval-" + UUID.randomUUID(), input, "eval:" + c.taskId(),
                    contractReq, castList(contractReq.get("requiredArtifactKinds")),
                    Boolean.TRUE.equals(contractReq.get("evidenceRequired")));
            var created = app.createRun(owner, sessionId, req);
            runId = UUID.fromString(created.runId());
            eval.attachRun(trialId,runId);

            // 等待终态（同步轮询；生产用事件驱动）。
            // 等待态也算观测完成：模糊类预期 waiting_user、权限类预期 waiting_approval（见 grade()），
            // 继续轮询只会白等到超时。
            var deadline = Instant.now().plus(Duration.ofSeconds(timeoutSeconds));
            while (Instant.now().isBefore(deadline)) {
                var row = runs.findByIdOwner(owner, runId).orElse(null);
                if (row != null && (row.status().terminal()
                        || row.status() == com.agentops.domain.AgentDomain.RunStatus.WAITING_USER
                        || row.status() == com.agentops.domain.AgentDomain.RunStatus.WAITING_APPROVAL)) {
                    finalStatus = row.status().db();
                    break;
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if ("unknown".equals(finalStatus)) {
                errorType = "TIMEOUT";
            }
            // 清理：等待态用例结束后取消 run，避免残留 pending 批准/等待任务
            if (!"succeeded".equals(finalStatus)
                    && !"failed".equals(finalStatus)) {
                try {
                    app.requestCancel(owner, runId);
                } catch (RuntimeException ignored) {
                }
            }
        } catch (RunApplicationService.ConflictException e) {
            errorType = "CONFLICT";
        } catch (RuntimeException e) {
            errorType = "ERROR:" + e.getMessage();
        }

        if(fixtureId!=null) knowledge.deleteDocument(owner,fixtureId);
        long duration = Instant.now().toEpochMilli() - start;

        // 规则评分（rule grader，文档 26.4）
        double score = grade(c, finalStatus);
        var calls=runId==null?List.<com.agentops.persistence.ExecutionRepo.ToolCallRow>of():execution.listToolCalls(owner.tenantId(),runId);
        var allowed=json.list(c.allowedToolsJson());
        boolean toolsValid=calls.stream().allMatch(call -> allowed.contains(call.toolName()));
        boolean requiredTool="normal".equals(c.category()) && !allowed.isEmpty();
        boolean toolSucceeded=calls.stream().anyMatch(call -> "succeeded".equals(call.status()));
        if(!toolsValid || (requiredTool && !toolSucceeded)) {
            score=0; errorType=!toolsValid?"UNEXPECTED_TOOL":"EXPECTED_TOOL_NOT_SUCCEEDED";
        }
        boolean passed = score >= 0.6 && errorType == null;
        if (!passed && errorType == null) {
            errorType = "STATUS_" + finalStatus.toUpperCase();
        }

        long tokens = 0;
        if (runId != null) {
            var row = runs.findByIdOwner(owner, runId).orElse(null);
            if (row != null) {
                tokens = row.tokenUsed();
                var captured=json.tree(row.configSnapshotJson());
                activeModel=new com.agentops.model.ModelSettingsService.ChatConfig("",captured.path("provider").asText(),"",
                        captured.path("chatModel").asText(),"");
            }
        }
        eval.finishTrial(trialId, score, passed, errorType, duration, tokens,
                json.write(Map.of("finalStatus",finalStatus,"provider",activeModel.provider(),
                        "model",activeModel.model(),"toolNames",calls.stream().map(com.agentops.persistence.ExecutionRepo.ToolCallRow::toolName).toList(),
                        "runId",runId==null?"":runId.toString())));
        eval.insertGrade(trialId,"completion",score,1.0,"rule","实际状态="+finalStatus+"；工具约束="+toolsValid);
        return new TrialSummaryView(c.taskId(), trialNo, score, passed, errorType, duration);
    }

    /** 规则评分：正常类要求 SUCCEEDED；模糊类要求 WAITING_USER（澄清而非假设）；权限类允许等待批准。 */
    private double grade(EvalRepo.CaseRow c, String finalStatus) {
        return switch (c.category()) {
            case "normal" -> "succeeded".equals(finalStatus) ? 1.0 : 0.0;
            case "ambiguous" -> "waiting_user".equals(finalStatus)
                    ? 1.0 : 0.2;
            case "permission" -> "waiting_approval".equals(finalStatus) ? 1.0 : 0.0;
            case "fault" -> ("retry_wait".equals(finalStatus) || "succeeded".equals(finalStatus))
                    ? 0.8 : 0.2;
            default -> "succeeded".equals(finalStatus) ? 0.8 : 0.0;
        };
    }

    /** 查询套件结果（用于回归对比，文档 26.4）。 */
    public Map<String, Object> suiteReport(Principal owner,UUID suiteId) {
        requireSuite(owner,suiteId);
        var allTrials = eval.listTrials(suiteId);
        var trials = allTrials.stream()
                .<Map<String, Object>>map(t -> Map.of(
                        "taskId", t.taskId(), "trialNo", t.trialNo(),
                        "score", t.score(), "passed", t.passed(),
                        "errorType", t.errorType() == null ? "" : t.errorType(),
                        "durationMs", t.durationMs() == null ? 0 : t.durationMs(),"usedTokens",t.usedTokens(),
                        "trace",t.traceJson()==null?Map.of():json.map(t.traceJson())))
                .toList();
        long passed = allTrials.stream().filter(EvalRepo.TrialSummary::passed).count();
        long total = allTrials.size();
        double avg = total == 0 ? 0 : allTrials.stream()
                .mapToDouble(EvalRepo.TrialSummary::score).average().orElse(0);
        return Map.of("suiteId", suiteId.toString(), "total", total, "passed", passed,
                "avgScore", avg, "trials", trials);
    }

    private void requireSuite(Principal owner,UUID suiteId) {
        if(!eval.ownsSuite(owner.tenantId(),suiteId)) throw new RunApplicationService.NotFoundException("评测套件不存在");
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object o) {
        if (o instanceof List<?> list) {
            var result = new ArrayList<String>();
            for (var item : list) result.add(String.valueOf(item));
            return result;
        }
        return List.of();
    }
}
