package com.agentops.tool;

import com.agentops.domain.AgentDomain.EffectClass;
import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.ToolTypes.ToolResult;
import com.agentops.domain.ToolTypes.ToolSpec;
import com.agentops.model.ModelPort.ExecutionControl;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表（文档 11.1）：
 * 每个 Tool 在注册时声明名称、用途、输入 Schema、副作用类别、超时与返回上限；
 * 副作用类别由开发者声明，不由模型填写。
 */
@Component
public class ToolRegistry {

    /** 业务工具实现：由统一入口调用，内部可访问业务服务；必须使用已验证主体。 */
    @FunctionalInterface
    public interface ToolHandler {
        ToolResult execute(Principal principal, Map<String, Object> arguments,
                           ToolContext context) throws Exception;
    }

    /** 工具执行上下文：携带任务定位信息，禁止用于传递可被模型影响的身份。 */
    public record ToolContext(java.util.UUID runId, java.util.UUID stepId, String effectKey,
                              ExecutionControl control) {
    }

    public record RegisteredTool(ToolSpec spec, ToolHandler handler) {
    }

    private final Map<String, RegisteredTool> tools = new ConcurrentHashMap<>();

    /** 注册工具；名称冲突直接失败（文档 11.1 名称在本次工具集合内唯一）。 */
    public void register(ToolSpec spec, ToolHandler handler) {
        if (tools.containsKey(spec.name())) {
            throw new IllegalStateException("工具重复注册: " + spec.name());
        }
        tools.put(spec.name(), new RegisteredTool(spec, handler));
    }

    public Optional<RegisteredTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Optional<ToolSpec> spec(String name) {
        return find(name).map(RegisteredTool::spec);
    }

    public Collection<RegisteredTool> all() {
        return List.copyOf(tools.values());
    }

    /** 简化的 Schema 构造器。 */
    public static ToolSpec spec(String name, String description, EffectClass effect,
                                boolean requiresApproval, int maxResultBytes,
                                Map<String, ToolSpec.PropertySpec> schema) {
        return new ToolSpec(name, "1.0.0", description, schema, effect, requiresApproval,
                Duration.ofSeconds(30), maxResultBytes);
    }

    /** 便捷：按名称顺序构建 schema（调用顺序即属性顺序）。 */
    public static Map<String, ToolSpec.PropertySpec> schema(String[] names,
                                                            ToolSpec.PropertySpec[] props) {
        var map = new LinkedHashMap<String, ToolSpec.PropertySpec>();
        for (int i = 0; i < names.length; i++) map.put(names[i], props[i]);
        return map;
    }

    /** 本次任务允许的工具名集合（可用于按任务筛选，文档 11.3）。 */
    public Set<String> names() {
        return Set.copyOf(tools.keySet());
    }
}
