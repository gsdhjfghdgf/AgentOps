package com.agentops.model;

import com.agentops.domain.Primitives.RunSnapshot;
import com.agentops.domain.RunMode;
import com.agentops.domain.ToolTypes.ToolSpec;
import com.agentops.infra.Json;
import java.util.*;
import java.util.regex.Pattern;

/** Model exposure is a context optimization. PolicyService still owns every execution decision. */
public final class ModelToolCatalog {
    private static final Set<String> BASE = Set.of("calculator", "current_time", "web_search", "web_read",
            "knowledge_search", "tool_discover", "conversation_history");
    private static final Map<String, Pattern> HINTS = Map.ofEntries(
            Map.entry("project", Pattern.compile("(?i)项目|代码|目录|编程|修改|创建|开发|project|code|git|build")),
            Map.entry("web", Pattern.compile("(?i)网页|联网|搜索|新闻|近期|最新|浏览器|网站|https?://|search|browser|website")),
            Map.entry("skills", Pattern.compile("(?i)技能|依赖|skills?|node|python")),
            Map.entry("knowledge", Pattern.compile("(?i)知识库|记忆|偏好|knowledge|memory")),
            Map.entry("mcp", Pattern.compile("(?i)mcp|github|issue|pull request")),
            Map.entry("reports", Pattern.compile("(?i)报告|销售|周报|导出|word|report|sales")),
            Map.entry("messaging", Pattern.compile("(?i)邮件|通知|发信|email|send_email")),
            Map.entry("presentations", Pattern.compile("(?i)ppt|powerpoint|幻灯|演示文稿|演讲")),
            Map.entry("spreadsheets", Pattern.compile("(?i)表格|excel|xlsx|csv|统计|图表|chart|spreadsheet")),
            Map.entry("documents", Pattern.compile("(?i)文档|pdf|word|docx|阅读文件|合并|提取")),
            Map.entry("media", Pattern.compile("(?i)图片|图像|png|jpeg|尺寸|缩放|image")),
            Map.entry("archives", Pattern.compile("(?i)zip|压缩|归档|打包")),
            Map.entry("files", Pattern.compile("(?i)文件|文档|ppt|pdf|excel|word|xlsx|docx|png|图片|图表|zip|file|artifact")));

    private ModelToolCatalog() {}

    public static String group(String name) {
        if (name.startsWith("presentation_")) return "presentations";
        if (name.startsWith("spreadsheet_") || name.equals("data_chart")) return "spreadsheets";
        if (name.startsWith("document_") || name.startsWith("pdf_")) return "documents";
        if (name.startsWith("image_")) return "media";
        if (name.startsWith("archive_")) return "archives";
        if (name.startsWith("artifact_")) return "files";
        if (name.startsWith("project_") || name.startsWith("workspace_")
                || Set.of("command_run","sandbox_run","plan_update","task_notes").contains(name)) return "project";
        if (name.startsWith("web_") || name.startsWith("browser_")) return "web";
        if (name.startsWith("skill_") || name.startsWith("runtime_")) return "skills";
        if (name.startsWith("mcp_")) return "mcp";
        if (name.startsWith("knowledge_") || name.startsWith("memory_")) return "knowledge";
        if (name.startsWith("report_") || Set.of("sales_query","csv_analyze","text_file_create").contains(name)) return "reports";
        if (name.equals("send_email")) return "messaging";
        return "other";
    }

    public static List<ToolSpec> discover(String group, List<ToolSpec> visible) {
        if (!group.equals("all") && !HINTS.containsKey(group)) throw new IllegalArgumentException("未知工具分组");
        return visible.stream().filter(tool -> group.equals("all") || group(tool.name()).equals(group)).toList();
    }

    public static List<ToolSpec> select(RunSnapshot run, List<ToolSpec> visible,
                                       List<ModelPort.ModelMessage> messages, Json json) {
        if (visible == null) return List.of();
        if (visible.size() <= 8) return List.copyOf(visible); // Explicit small contract allow-lists.
        String goal = run == null || run.workingState() == null ? "" : Objects.toString(run.workingState().goal(),"");
        // Excluded scope is not a reason to attach those schemas. Original requirements remain unchanged.
        String requestedScope=goal.replaceAll("(?i)(不需要|无需|不要|禁止|without)[^，。；;\\n]{0,40}","");
        var groups = new HashSet<String>();
        HINTS.forEach((name, pattern) -> { if (pattern.matcher(requestedScope).find()) groups.add(name); });
        var names = new HashSet<>(BASE);
        if(groups.contains("presentations"))names.addAll(Set.of("image_search","image_download","image_info"));
        if (run == null || run.mode() != RunMode.CHAT) {
            names.addAll(Set.of("plan_update","task_notes","skill_use"));
            if (groups.isEmpty()) groups.add("project");
        }
        var calls = new HashMap<String,String>();
        for (var message : messages.subList(Math.max(0,messages.size()-24),messages.size())) {
            for (var call : message.toolCalls()) {
                calls.put(call.id(),call.name());
                if (!call.name().equals("tool_discover")) groups.add(group(call.name()));
            }
            if ("tool".equals(message.role()) && "tool_discover".equals(calls.get(message.toolCallId()))) {
                try { for (var name : json.tree(message.content()).path("tools")) names.add(name.asText()); }
                catch (RuntimeException ignored) { /* Failed discovery cannot expand the catalog. */ }
            }
        }
        // Intersect with the fresh authorization-visible catalog, including after reconnect/recovery.
        return visible.stream().filter(tool -> names.contains(tool.name()) || groups.contains(group(tool.name())))
                .sorted(Comparator.comparing(ToolSpec::name)).toList();
    }
}
