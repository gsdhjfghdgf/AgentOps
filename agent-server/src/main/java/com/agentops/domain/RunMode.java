package com.agentops.domain;

import com.agentops.domain.Primitives.RunSnapshot;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Persisted per run. Model instructions and user-approved tools cannot change these bounds. */
public enum RunMode {
    CHAT, PLAN, BUILD;

    private static final Set<String> QUERY_TOOLS = Set.of(
            "project_list", "project_read", "project_search", "project_git_status", "project_git_diff",
            "workspace_list", "workspace_read", "workspace_search",
            "knowledge_search", "knowledge_list", "knowledge_read", "memory_list", "sales_query",
            "calculator", "current_time", "csv_analyze", "web_search", "web_read",
            "browser_open", "browser_snapshot", "browser_close",
            "skill_use", "skill_list", "skill_dependencies", "skill_preview", "mcp_connections", "mcp_tools");
    private static final Pattern GREETING = Pattern.compile(
            "(?i)^(你好|您好|嗨|hi|hello|早上好|下午好|晚上好|谢谢|感谢|你是谁)[！!。？?\\s]*$");

    public static RunMode parse(String value) {
        if (value == null || value.isBlank()) return BUILD; // Existing API clients and scheduled tasks.
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("模式必须为 chat、plan 或 build"); }
    }

    public String value() { return name().toLowerCase(Locale.ROOT); }

    public boolean allows(String tool) {
        if (tool.equals("tool_discover") || tool.equals("conversation_history")) return true;
        // Chat can research a question, including approved MCP queries, without starting a development workflow.
        // MCP calls retain their existing per-call approval; a mode never grants network or other permissions.
        return this == BUILD || QUERY_TOOLS.contains(tool)
                || (this == CHAT && Set.of("mcp_call", "browser_screenshot").contains(tool))
                || (this == PLAN && Set.of("plan_update", "task_notes").contains(tool));
    }

    public static boolean directConversation(RunSnapshot run) {
        return run != null && run.workingState() != null && run.workingState().goal() != null
                && GREETING.matcher(run.workingState().goal().trim()).matches();
    }
}
