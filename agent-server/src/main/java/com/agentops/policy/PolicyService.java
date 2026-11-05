package com.agentops.policy;

import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.Primitives.RunSnapshot;
import com.agentops.domain.ToolTypes.PolicyDecision;
import com.agentops.domain.ToolTypes.ToolIntent;
import com.agentops.domain.ToolTypes.ToolSpec;
import com.agentops.infra.Json;
import com.agentops.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 策略服务（文档 15 节三道检查）：
 * 1. 可见性：按用户和任务筛选工具；
 * 2. 参数/资源：检查 Schema、值域、资源归属；
 * 3. 实际授权：结合主体、角色与工具风险返回 ALLOW / DENY / REQUIRE_APPROVAL。
 *
 * 模型不能通过修改 userId/tenantId 或路径扩大权限；执行前（含恢复后）
 * 必须重新检查当前权限，不沿用旧允许结果。
 */
@Service
public class PolicyService {

    private static final Logger log = LoggerFactory.getLogger(PolicyService.class);

    private final ToolRegistry registry;
    private final Json json;
    private final Clock clock;
    private final com.agentops.persistence.CapabilityRepo capabilities;
    private final com.agentops.persistence.RunRepo runs;

    public PolicyService(ToolRegistry registry, Json json, Clock clock,com.agentops.persistence.CapabilityRepo capabilities,
                         com.agentops.persistence.RunRepo runs) {
        this.registry = registry;
        this.json = json;
        this.clock = clock;
        this.capabilities=capabilities;
        this.runs=runs;
    }

    /** 可见工具（文档 15.1 第一道）。首版：注册表内全部工具对该任务可见，可按任务类型扩展白名单。 */
    public List<ToolSpec> visibleTools(RunSnapshot run) {
        var specs = new ArrayList<ToolSpec>();
        var preferences=capabilities.preferences(run.key().owner(),"tool");
        for (var tool : registry.all()) {
            if (!run.mode().allows(tool.spec().name())) continue;
            // Ordinary conversational preferences belong to automatic summarization. Expose the manual
            // writer only for an explicit memory-management task, avoiding unnecessary approval waits.
            if("memory_store".equals(tool.spec().name())) {
                String goal=run.workingState()==null?"":java.util.Objects.toString(run.workingState().goal(),"");
                if(!java.util.regex.Pattern.compile("(?i)记忆|memory|手动|立即保存|修改偏好|更新偏好|保存偏好|偏好设置|saved preference").matcher(goal).find())continue;
            }
            var preference=preferences.get(tool.spec().name());
            if(preference!=null && !preference.enabled())continue;
            if(preference==null && "command_run".equals(tool.spec().name()))continue;
            if (run.contract()!=null && run.contract().requirements().get("allowedTools") instanceof List<?> allowed
                    && !allowed.contains(tool.spec().name())) continue;
            specs.add(tool.spec());
        }
        return specs;
    }

    /**
     * 派发前评估（文档 15.1 第二、三道 + 15.2 digest）。
     * 执行前再次检查当前权限：批准或故障恢复后同样调用本方法。
     */
    public PolicyDecision evaluate(Principal principal, RunSnapshot run, ToolIntent intent) {
        if(principal==null||run==null||!principal.equals(run.key().owner()))return PolicyDecision.deny("RUN_OWNER_MISMATCH");
        if(intent==null||intent.toolName()==null||intent.arguments()==null)return PolicyDecision.deny("INVALID_TOOL_INTENT");
        if (!run.mode().allows(intent.toolName())) return PolicyDecision.deny("MODE_TOOL_DENIED:" + run.mode().value());
        // 第一道：可见性
        var spec = registry.spec(intent.toolName());
        if (spec.isEmpty()) {
            return PolicyDecision.deny("TOOL_NOT_REGISTERED");
        }
        boolean visible = visibleTools(run).stream().anyMatch(s -> s.name().equals(intent.toolName()));
        if (!visible) {
            log.warn("工具不可见 run={} tool={}", run.key().runId(), intent.toolName());
            return PolicyDecision.deny("TOOL_NOT_VISIBLE");
        }

        // 资源归属：参数中不得出现其他租户/用户标识（文档 15.1：模型不能通过修改身份扩大权限）
        for (var entry : intent.arguments().entrySet()) {
            String k = entry.getKey() == null ? "" : entry.getKey().toLowerCase();
            Object v = entry.getValue();
            if ((k.contains("tenant") || k.contains("user_id") || k.equals("userid"))
                    && v != null && !v.toString().isBlank()) {
                log.warn("参数包含身份字段拒绝执行 tool={} param={}", intent.toolName(), k);
                return PolicyDecision.deny("IDENTITY_OVERRIDE_ATTEMPT");
            }
        }

        // 第二道：参数 Schema（必需字段与类型）
        String schemaError = validateArguments(spec.get(), intent.arguments());
        if (schemaError != null) {
            return PolicyDecision.deny(schemaError);
        }

        // 第三道：实际授权（副作用风险分级）
        String digest = actionDigest(principal, intent);
        if("sandbox_run".equals(intent.toolName())&&runs.hasSandboxPermission(principal,run.key().runId()))return PolicyDecision.allow(digest);
        // Selecting a project grants file access inside its canonical root. Each handler still
        // checks the pinned project owner, traversal/symlinks and optimistic write hashes.
        // Tool disablement, argument validation and identity checks above remain mandatory.
        if (com.agentops.project.ProjectTools.isProjectFileTool(intent.toolName()))
            return PolicyDecision.allow(digest);
        if (com.agentops.persistence.RunRepo.isPublicWebTool(intent.toolName())
                && runs.hasNetworkPermission(principal, run.key().runId())) return PolicyDecision.allow(digest);
        if ("command_run".equals(intent.toolName()) && runs.hasCommandPermission(principal,run.key().runId()))
            return PolicyDecision.allow(digest);
        if (com.agentops.persistence.RunRepo.isBrowserInteraction(intent.toolName()) && runs.hasBrowserPermission(principal,run.key().runId()))
            return PolicyDecision.allow(digest);
        var preference=capabilities.preferences(principal,"tool").get(intent.toolName());
        if (spec.get().requiresApproval() || (preference!=null && preference.askApproval())) {
            return PolicyDecision.requireApproval(digest);
        }
        return PolicyDecision.allow(digest);
    }

    /**
     * action_digest = hash(tenant + user + tool + version + canonical_arguments)（文档 15.2）。
     * 规范化参数：按 key 排序的稳定 JSON，保证参数变化必然改变 digest。
     */
    public String actionDigest(Principal principal, ToolIntent intent) {
        var canonical = new TreeMap<String, Object>();
        intent.arguments().forEach((k, v) -> canonical.put(k, v == null ? "" : v.toString()));
        String material = String.join("|",
                principal.tenantId().toString(), principal.userId().toString(),
                intent.toolName(), intent.toolVersion(), json.write(canonical));
        return sha256Hex(material);
    }

    /** 规范化参数哈希（工具幂等键的一部分）。 */
    public String argumentsHash(Map<String, Object> arguments) {
        var canonical = new TreeMap<String, Object>();
        arguments.forEach((k, v) -> canonical.put(k, v == null ? "" : v.toString()));
        return sha256Hex(json.write(canonical));
    }

    private String validateArguments(ToolSpec spec, Map<String, Object> arguments) {
        var schema = spec.inputSchema();
        var provided = new LinkedHashMap<String, Object>(arguments == null ? Map.of() : arguments);
        if(provided.containsKey("_raw"))return "INVALID_ARGUMENT_JSON";
        for (var entry : schema.entrySet()) {
            var prop = entry.getValue();
            Object value = provided.get(entry.getKey());
            if (prop.required() && (value == null || value.toString().isBlank())) {
                return "MISSING_ARGUMENT:" + entry.getKey();
            }
            if(value!=null && "string".equals(prop.type()) && !(value instanceof String))return "ARGUMENT_TYPE:"+entry.getKey();
            int maximum=java.util.Set.of("content","csv","filesJson","stepsJson","argumentsJson","deckJson","changesJson","sheetsJson","dataJson").contains(entry.getKey())?24000:4000;
            if (value != null && "string".equals(prop.type()) && value.toString().length() > maximum) {
                return "ARGUMENT_TOO_LONG:" + entry.getKey();
            }
        }
        for (var key : provided.keySet()) {
            if (!schema.containsKey(key)) {
                return "UNKNOWN_ARGUMENT:" + key;
            }
        }
        return null;
    }

    private String sha256Hex(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
