package com.agentops.tool;

import com.agentops.artifact.ArtifactService;
import com.agentops.domain.AgentDomain.EffectClass;
import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.ToolTypes.ToolResult;
import com.agentops.domain.ToolTypes.ToolSpec;
import com.agentops.tool.ToolRegistry.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * 报告生成工具：IDEMPOTENT_WRITE（相同 effectKey 生成相同标题报告，网关层去重）。
 * 产物先写暂存文件，经 ArtifactService 登记后返回对象 ID（文档 19.2 / 32.3：
 * 文件使用服务端分配的对象 ID，不信任模型提供的任意本地路径）。
 *
 * 说明：这里生成 Markdown 报告（生产可替换为 PDF 渲染组件，接口不变）。
 */
@Component
public class ReportTools implements ToolRegistry.ToolHandler {

    private static final Logger log = LoggerFactory.getLogger(ReportTools.class);

    private final ArtifactService artifacts;
    private final SalesTools sales;

    public ReportTools(ArtifactService artifacts, SalesTools sales) {
        this.artifacts = artifacts;
        this.sales = sales;
    }

    public static ToolSpec spec() {
        return ToolRegistry.spec("report_generate",
                "根据已查询的销售数据生成报告文件，返回产物对象 ID 供用户下载。format 支持 markdown/pdf（当前实现为 markdown）。",
                EffectClass.IDEMPOTENT_WRITE, false, 4096,
                ToolRegistry.schema(
                        new String[]{"title", "format", "startDate", "endDate"},
                        new ToolSpec.PropertySpec[]{
                                ToolSpec.PropertySpec.req("string", "报告标题"),
                                ToolSpec.PropertySpec.opt("string", "输出格式：markdown 或 pdf"),
                                ToolSpec.PropertySpec.opt("string", "统计开始日期 YYYY-MM-DD"),
                                ToolSpec.PropertySpec.opt("string", "统计结束日期 YYYY-MM-DD")
                        }));
    }

    @Override
    public ToolResult execute(Principal principal, Map<String, Object> arguments, ToolContext context) throws Exception {
        String title = arguments.get("title") == null ? "销售报告" : arguments.get("title").toString();
        String format = arguments.get("format") == null ? "markdown" : arguments.get("format").toString();
        String start = arguments.get("startDate") == null
                ? LocalDate.now().withDayOfMonth(1).toString() : arguments.get("startDate").toString();
        String end = arguments.get("endDate") == null
                ? LocalDate.now().toString() : arguments.get("endDate").toString();

        // 报告内容来自真实工具数据（不是模型编造）：复用 sales_query 的同源数据
        var dataArgs = Map.<String, Object>of("startDate", start, "endDate", end);
        var salesResult = sales.execute(principal, dataArgs, context);
        if (salesResult.status() != com.agentops.domain.AgentDomain.ToolOutcomeStatus.SUCCEEDED) {
            return ToolResult.failed("SALES_DATA_UNAVAILABLE", "销售数据不可用，无法生成报告");
        }

        String markdown = """
                # %s

                > 生成时间：%s（服务端生成，数据来源：销售数据库）

                ## 统计说明

                - 统计周期：%s 至 %s（结束日期不含当天）
                - 币种：人民币（CNY）
                - 口径：自然日含税销售额

                ## 数据明细

                %s

                ## 校验说明

                本报告由服务端基于真实查询结果生成，指标可按上方口径复核。
                """.formatted(title, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                start, end, salesResult.modelSafeText());

        UUID objectId = UUID.randomUUID();
        var staging = artifacts.stagingPath(principal.tenantId(), objectId, ".md");
        Files.writeString(staging, markdown, java.nio.charset.StandardCharsets.UTF_8);

        String fileName = sanitizeFileName(title) + ".md";
        var ref = artifacts.register(principal, null,
                context.runId(), fileName, "text/markdown", staging, "output");

        log.info("report_generate 完成 object={} title={}", ref.objectId(), title);
        return ToolResult.successWithArtifact(
                "报告已生成：\n- 文件名：" + fileName + "\n- 对象ID：" + ref.objectId()
                        + "\n- 大小：" + ref.byteSize() + " 字节\n- 下载：GET /api/artifacts/" + ref.objectId() + "/download",
                ref.objectId());
    }

    /** 标题不能直接当文件名（文档 32.1）。 */
    private String sanitizeFileName(String title) {
        String cleaned = title.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (cleaned.isEmpty()) cleaned = "report";
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }
}
