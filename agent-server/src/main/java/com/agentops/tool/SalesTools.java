package com.agentops.tool;

import com.agentops.domain.AgentDomain.EffectClass;
import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.ToolTypes.ToolResult;
import com.agentops.domain.ToolTypes.ToolSpec;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 销售查询工具（文档 20.1 本地 Tool 示例的落地）。
 *
 * 说明：这里用确定性模拟数据代替真实 SalesService，保证演示结果可核对：
 * 同一日期区间永远返回同一数据。接入真实业务时，只需替换 query() 的实现，
 * 并保持 principalProvider.currentVerifiedPrincipal() 的主体校验语义。
 */
@Component
public class SalesTools implements ToolRegistry.ToolHandler {

    private static final Logger log = LoggerFactory.getLogger(SalesTools.class);

    /** 模拟"数据库"：product → date → 销售额。 */
    private final Map<String, Map<LocalDate, BigDecimal>> dataset = new TreeMap<>();

    @PostConstruct
    void seed() {
        // 固定种子生成 90 天数据，保证演示可复现
        String[] products = {"智能音箱", "无线耳机", "智能手表"};
        long seed = 20261027L;
        LocalDate today = LocalDate.now();
        for (String product : products) {
            var byDate = new LinkedHashMap<LocalDate, BigDecimal>();
            for (int i = 0; i < 90; i++) {
                seed = (seed * 6364136223846793005L + 1442695040888963407L) >>> 0;
                int units = (int) (seed % 80) + 20;
                BigDecimal price = new BigDecimal(100 + (seed % 900));
                byDate.put(today.minusDays(i), price.multiply(BigDecimal.valueOf(units))
                        .setScale(2, RoundingMode.HALF_UP));
            }
            dataset.put(product, byDate);
        }
        log.info("SalesTools 模拟数据集已初始化：{} 个产品 × 90 天", products.length);
    }

    public static ToolSpec spec() {
        return ToolRegistry.spec("sales_query",
                "查询指定日期范围内的分产品销售额汇总。结束日期不包含当天。参数格式：YYYY-MM-DD。",
                EffectClass.READ_ONLY, false, 8192,
                ToolRegistry.schema(
                        new String[]{"startDate", "endDate"},
                        new ToolSpec.PropertySpec[]{
                                ToolSpec.PropertySpec.req("string", "开始日期，格式 YYYY-MM-DD"),
                                ToolSpec.PropertySpec.req("string", "结束日期（不含），格式 YYYY-MM-DD")
                        }));
    }

    @Override
    public ToolResult execute(Principal principal, Map<String, Object> arguments, ToolRegistry.ToolContext context) {
        // 参数事实校验：JSON 格式正确不代表事实正确（文档 11.2）
        String startRaw = str(arguments.get("startDate"));
        String endRaw = str(arguments.get("endDate"));
        LocalDate start = parseDate(startRaw, "startDate");
        LocalDate end = parseDate(endRaw, "endDate");
        if (!start.isBefore(end)) {
            return ToolResult.failed("INVALID_RANGE", "startDate 必须早于 endDate");
        }
        if (start.isBefore(LocalDate.now().minusDays(90))) {
            return ToolResult.failed("RANGE_NOT_SUPPORTED", "演示数据仅覆盖最近 90 天，请缩小范围");
        }

        var rows = new ArrayList<Map<String, Object>>();
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : dataset.entrySet()) {
            BigDecimal sum = BigDecimal.ZERO;
            long days = 0;
            for (var dateSale : entry.getValue().entrySet()) {
                var d = dateSale.getKey();
                if (!d.isBefore(start) && d.isBefore(end)) {
                    sum = sum.add(dateSale.getValue());
                    days++;
                }
            }
            if (days > 0) {
                var row = new LinkedHashMap<String, Object>();
                row.put("product", entry.getKey());
                row.put("salesAmount", sum.setScale(2, RoundingMode.HALF_UP));
                row.put("days", days);
                rows.add(row);
                total = total.add(sum);
            }
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("period", Map.of("startDate", start.toString(), "endDateExclusive", end.toString()));
        result.put("currency", "CNY");
        result.put("byProduct", rows);
        result.put("totalAmount", total.setScale(2, RoundingMode.HALF_UP));
        result.put("source", "mock-sales-db（确定性模拟数据，口径：自然日含税销售额）");

        String text = formatText(result);
        log.info("sales_query principal={} period={}~{} total={}", principal.userId(), start, end, total);
        return ToolResult.success(text);
    }

    private String formatText(Map<String, Object> result) {
        var sb = new StringBuilder();
        sb.append("销售数据查询结果\n");
        sb.append("统计口径：").append(result.get("source")).append("\n");
        @SuppressWarnings("unchecked")
        var period = (Map<String, Object>) result.get("period");
        sb.append("统计周期：").append(period.get("startDate")).append(" 至 ")
                .append(period.get("endDateExclusive")).append("（不含）\n");
        sb.append("币种：").append(result.get("currency")).append("\n\n");
        sb.append("分产品：\n");
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) result.get("byProduct");
        for (var row : rows) {
            sb.append("- ").append(row.get("product")).append("：")
                    .append(row.get("salesAmount")).append(" 元（").append(row.get("days")).append(" 天）\n");
        }
        sb.append("\n合计销售额：").append(result.get("totalAmount")).append(" 元");
        return sb.toString();
    }

    private String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private LocalDate parseDate(String raw, String field) {
        if (raw == null || !raw.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new IllegalArgumentException(field + " 必须为 YYYY-MM-DD 格式");
        }
        return LocalDate.parse(raw);
    }
}
