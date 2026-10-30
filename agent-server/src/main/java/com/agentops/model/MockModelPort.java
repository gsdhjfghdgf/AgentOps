package com.agentops.model;

import com.agentops.domain.ModelDecision;
import com.agentops.domain.Primitives.RunSnapshot;
import com.agentops.domain.ResultTypes.CandidateAnswer;
import com.agentops.domain.ToolTypes.ToolSpec;
import com.agentops.infra.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Mock 模型：可脚本化的模拟模型，让系统在没有 API Key 时也能跑通完整流程。
 *
 * 设计要点：
 * 1. 无内存状态——决策完全由持久化的消息转录重建（文档 13.2：每个推进
 *    边界都能从数据库重建，恢复后不依赖 JVM 内存）。
 * 2. 按输入关键词匹配剧本（scenario），驱动不同的工具链，用于演示：
 *    sales_query（只读查询）/ report_generate（产物生成）/
 *    send_email（非幂等写，需批准）/ knowledge_search（RAG 检索）/
 *    澄清提问（AskUser）/ 直接回答（DIRECT 策略）。
 * 3. 最终答案通过 provisional 回调逐段推送，模拟流式增量。
 */
@Component
public class MockModelPort implements ModelPort {

    private static final Logger log = LoggerFactory.getLogger(MockModelPort.class);

    private final Json json;

    public MockModelPort(Json json) {
        this.json = json;
    }

    /** 剧本：一个有序工具链 + 收尾答案模板。 */
    private record Scenario(String name, List<ToolPlan> tools, String finalAnswer) {
    }

    private record ToolPlan(String tool, Map<String, Object> args) {
    }

    @Override
    public ModelDecision next(RunSnapshot snapshot, String systemPrompt, List<ModelMessage> messages,
                              List<ToolSpec> allowedTools, ExecutionControl control,
                              Consumer<String> provisional) {
        var last = messages.get(messages.size() - 1);

        // ---- 已有工具结果：根据转录中的调用链决定继续或收尾 ----
        if ("tool".equals(last.role())) {
            return continueAfterTools(snapshot, messages, allowedTools, provisional);
        }

        // ---- user 请求：匹配剧本 ----
        String input = last.content() == null ? "" : last.content();
        var scenario = matchScenario(input, allowedTools);

        if (scenario == null) {
            // 澄清：未给关键条件（评测类别 ambiguous，文档 26.2）
            return new ModelDecision.AskUser(UUID.randomUUID(),
                    "请选择查询的时间范围，也可以自行填写具体要求。",List.of("查询本月销售数据","查询上月销售数据","生成上周销售报告"));
        }
        if (scenario.tools().isEmpty()) {
            return finalCandidate(genericAnswer(input), provisional);
        }
        return nextToolCall(scenario, 0, "好的，我来处理：" + describe(scenario));
    }

    /** 第 stepIndex 个工具调用。 */
    private ModelDecision nextToolCall(Scenario scenario, int stepIndex, String assistantText) {
        var plan = scenario.tools().get(stepIndex);
        var call = new com.agentops.domain.ToolTypes.ToolCallRequest(
                "call_" + UUID.randomUUID(), plan.tool(), plan.args());
        return new ModelDecision.ToolBatch(assistantText, List.of(call));
    }

    /** 工具批次执行完毕回到模型：决定下一个工具或形成候选答案。 */
    private ModelDecision continueAfterTools(RunSnapshot snapshot, List<ModelMessage> messages,
                                             List<ToolSpec> allowedTools, Consumer<String> provisional) {
        // 从转录重建：提取本 run 内已调用的工具序列（恢复后依然正确）
        var executed = new ArrayList<String>();
        var toolResults = new LinkedHashMap<String, String>();
        String lastUserInput = "";
        for (var m : messages) {
            if ("user".equals(m.role()) && m.content() != null) lastUserInput = m.content();
            if ("assistant".equals(m.role()) && m.toolCalls() != null) {
                for (var c : m.toolCalls()) executed.add(c.name());
            }
            if ("tool".equals(m.role()) && m.toolCallId() != null) {
                toolResults.put(m.toolCallId(), m.content());
            }
        }

        var scenario = matchScenario(lastUserInput, allowedTools);
        if (scenario == null) {
            return finalCandidate(genericAnswer(lastUserInput), provisional);
        }

        int done = executed.size();
        if (done < scenario.tools().size()) {
            var plan = scenario.tools().get(done);
            var call = new com.agentops.domain.ToolTypes.ToolCallRequest(
                    "call_" + UUID.randomUUID(), plan.tool(), plan.args());
            return new ModelDecision.ToolBatch("继续处理剩余步骤。", List.of(call));
        }

        // 全部工具完成：基于真实工具结果生成候选答案
        String evidence = summarizeResults(toolResults);
        String answer = scenario.finalAnswer() + "\n\n" + evidence;
        return finalCandidate(answer, provisional);
    }

    /** 最终候选：通过 provisional 推送正文增量（模拟流式），再返回完整候选。 */
    private ModelDecision finalCandidate(String answer, Consumer<String> provisional) {
        // 分段推送，前端才能看到流式效果；这里每段 24 字符
        for (int i = 0; i < answer.length(); i += 24) {
            provisional.accept(answer.substring(i, Math.min(answer.length(), i + 24)));
        }
        var revisionId = UUID.randomUUID();
        var candidate = new CandidateAnswer(revisionId, answer, List.of(), List.of(), Map.of());
        return new ModelDecision.FinalCandidate(candidate);
    }

    // ==================== 剧本匹配 ====================

    private Scenario matchScenario(String input, List<ToolSpec> allowedTools) {
        String in = input == null ? "" : input;
        boolean canSales = has(allowedTools, "sales_query");
        boolean canReport = has(allowedTools, "report_generate");
        boolean canEmail = has(allowedTools, "send_email");
        boolean canKnowledge = has(allowedTools, "knowledge_search");

        // 1. 生成周报/报告文件（多步骤 + 产物）
        if (containsAny(in, "周报", "报告", "报表") && containsAny(in, "生成", "导出", "做", "来一份")
                && canSales && canReport) {
            var tools = new ArrayList<ToolPlan>();
            tools.add(new ToolPlan("sales_query", Map.of("startDate", resolveStartDate(in), "endDate", resolveEndDate(in))));
            tools.add(new ToolPlan("report_generate", Map.of(
                    "title", "销售周报 " + resolveStartDate(in) + " 至 " + resolveEndDate(in),
                    "format", in.contains("pdf") ? "pdf" : "markdown")));
            return new Scenario("sales_report", tools,
                    "**处理完成**：已按要求生成销售报告，关键结论如下。\n");
        }

        // 2. 需批准的写操作演示（非幂等写：发送邮件）——优先于只读查询匹配，
        //    避免"给销售团队发邮件"这类消息因含"销售"被查询分支抢先
        if (containsAny(in, "邮件", "通知", "发送给") && canEmail) {
            var tools = new ArrayList<ToolPlan>();
            tools.add(new ToolPlan("send_email", Map.of(
                    "to", "manager@example.com",
                    "subject", "经营数据通知",
                    "body", "详见本次任务生成的统计数据。")));
            return new Scenario("send_email", tools,
                    "**操作已执行**：邮件已按批准的参数发送。\n");
        }

        // 3. 销售数据查询（单次只读查询）
        if (containsAny(in, "销售", "销量", "营收", "收入") && canSales) {
            var tools = new ArrayList<ToolPlan>();
            tools.add(new ToolPlan("sales_query", Map.of("startDate", resolveStartDate(in), "endDate", resolveEndDate(in))));
            return new Scenario("sales_query_only", tools,
                    "**查询结果**：以下为按约定口径统计的真实数据。\n");
        }

        // 4. 知识库检索（RAG）
        if (containsAny(in, "知识", "制度", "规范", "手册", "文档里", "查一下什么是") && canKnowledge) {
            var tools = new ArrayList<ToolPlan>();
            tools.add(new ToolPlan("knowledge_search", Map.of("query", in)));
            return new Scenario("knowledge", tools,
                    "**检索结果**：以下内容来自知识库原文。\n");
        }

        // 5. 模糊请求 → 澄清（不擅自假设关键条件，文档 26.2）
        if (in.trim().length() > 0 && in.trim().length() <= 6
                && containsAny(in, "查", "帮", "看", "统计")) {
            return null;
        }

        return new Scenario("direct", List.of(), "");
    }

    private boolean has(List<ToolSpec> tools, String name) {
        return tools != null && tools.stream().anyMatch(t -> t.name().equals(name));
    }

    private boolean containsAny(String input, String... keywords) {
        for (var k : keywords) {
            if (input.contains(k)) return true;
        }
        return false;
    }

    /** 从输入提取开始日期，缺省"本月 1 日"。 */
    private String resolveStartDate(String in) {
        var m = java.util.regex.Pattern.compile("(20\\d{2})[-/年](\\d{1,2})[-/月](\\d{1,2})").matcher(in);
        if (m.find()) {
            return "%s-%02d-%02d".formatted(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        var now = java.time.LocalDate.now();
        return now.withDayOfMonth(1).toString();
    }

    /** 结束日期：缺省今天。 */
    private String resolveEndDate(String in) {
        var m = java.util.regex.Pattern.compile("(?:至|到|-|—)(20\\d{2})[-/年](\\d{1,2})[-/月](\\d{1,2})").matcher(in);
        if (m.find()) {
            return "%s-%02d-%02d".formatted(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        return java.time.LocalDate.now().toString();
    }

    /** 把真实工具结果整理为给用户看的要点（工具输出是数据，不能提升权限，文档 28.1）。 */
    private String summarizeResults(Map<String, String> toolResults) {
        if (toolResults.isEmpty()) return "";
        var sb = new StringBuilder();
        sb.append("### 工具执行证据\n");
        for (var entry : toolResults.entrySet()) {
            var text = entry.getValue();
            if (text != null && text.length() > 1200) {
                text = text.substring(0, 1200) + "\n（结果已限长，完整内容见产物或明细）";
            }
            sb.append(text == null ? "" : text).append("\n");
        }
        return sb.toString();
    }

    private String describe(Scenario s) {
        return switch (s.name()) {
            case "sales_report" -> "先查询销售数据，再生成报告文件";
            case "sales_query_only" -> "查询销售数据";
            case "send_email" -> "发送通知邮件";
            case "knowledge" -> "在知识库中检索相关内容";
            default -> "直接处理";
        };
    }

    private String genericAnswer(String input) {
        return """
                已收到你的请求：%s

                当前为 Mock 模型（未配置真实 API Key）。你可以：
                - 输入“查询本月销售数据”体验只读工具链；
                - 输入“生成上周销售周报”体验多步骤 + 报告产物；
                - 输入“发送邮件通知”体验需批准的写操作；
                - 输入“查询差旅制度”体验知识库检索（需先导入文档）。

                配置 agent.model.provider=openai 与 API Key 后，本系统将使用真实模型。
                """.formatted(input == null ? "" : input.strip());
    }
}
