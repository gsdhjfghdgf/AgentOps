package com.agentops.tool;

import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.ToolTypes.ToolResult;
import com.agentops.domain.ToolTypes.ToolSpec;
import com.agentops.tool.ToolRegistry.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 本地工具执行适配器：统一入口、超时控制、异常隔离与结果限长。
 *
 * 约束（文档 11.3 / 28.2）：
 * - 错误返回稳定错误码与可修正信息，不把完整堆栈或凭证发送给模型；
 * - 原始输出持久保存必要部分，交给模型的是限长、过滤后的结果。
 */
@Component
public class LocalToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(LocalToolExecutor.class);

    private final ToolRegistry registry;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        var t = new Thread(r, "tool-exec");
        t.setDaemon(true);
        return t;
    });

    public LocalToolExecutor(ToolRegistry registry) {
        this.registry = registry;
    }

    /** 执行本地工具（在事务外调用，文档 1.3.3）。 */
    public ToolResult execute(Principal principal, ToolSpec spec, Map<String, Object> arguments,
                              ToolContext context) {
        var registered = registry.find(spec.name());
        if (registered.isEmpty()) {
            return ToolResult.failed("TOOL_NOT_REGISTERED", "工具未注册: " + spec.name());
        }

        Future<ToolResult> future = pool.submit(() -> registered.get().handler()
                .execute(principal, arguments, context));
        long timeoutMs = spec.timeout().toMillis();
        try {
            ToolResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return limit(result, spec.maxResultBytes());
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("工具执行超时 tool={} timeoutMs={}", spec.name(), timeoutMs);
            return ToolResult.failed("TOOL_TIMEOUT", "工具执行超时（" + timeoutMs + "ms）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolResult.failed("INTERRUPTED", "工具执行被中断");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            // 参数校验类异常返回可修正信息；其余记录日志、返回稳定错误码
            if (cause instanceof IllegalArgumentException) {
                log.info("工具参数不合法 tool={} msg={}", spec.name(), cause.getMessage());
                return ToolResult.failed("INVALID_ARGUMENT", cause.getMessage());
            }
            log.error("工具执行异常 tool={}", spec.name(), cause);
            return ToolResult.failed("TOOL_EXECUTION_ERROR", "工具内部错误，请调整参数或稍后重试");
        }
    }

    /** 结果限长：完整原始输出不直接进模型上下文（文档 11.3）。 */
    private ToolResult limit(ToolResult result, int maxBytes) {
        String text = result.modelSafeText();
        if (text == null) return result;
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return result;
        String truncated = new String(bytes, 0, maxBytes, java.nio.charset.StandardCharsets.UTF_8)
                + "\n（结果已限长截断）";
        return new ToolResult(result.status(), truncated, result.providerReference(),
                result.artifactId(), result.errorCode());
    }
}
