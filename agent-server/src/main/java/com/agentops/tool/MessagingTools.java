package com.agentops.tool;

import com.agentops.domain.AgentDomain.EffectClass;
import com.agentops.domain.Primitives.Principal;
import com.agentops.domain.ToolTypes.ToolResult;
import com.agentops.domain.ToolTypes.ToolSpec;
import com.agentops.tool.ToolRegistry.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 邮件发送工具：NON_IDEMPOTENT_WRITE 且 requiresApproval=true（文档 11.1 / 15 / 16.2）。
 *
 * 非幂等外部副作用的去重：网关按 effectKey 去重；本工具内部再用已送达
 * 集合兜底（模拟外部系统的业务唯一键）。结果未知时网关进入 WAITING_RECONCILE，
 * 不会盲目重发。
 */
@Component
public class MessagingTools implements ToolRegistry.ToolHandler {

    private static final Logger log = LoggerFactory.getLogger(MessagingTools.class);

    /** 模拟"邮件系统"已送达记录：effectKey → 送达回执。 */
    private final Map<String, String> delivered = new ConcurrentHashMap<>();

    public static ToolSpec spec() {
        return ToolRegistry.spec("send_email",
                "提交邮件发送意图；参数齐全就调用，系统会先暂停并展示批准卡，只有用户在批准卡中同意后才实际执行。不要在正文索取口头批准。当前邮件通道为演示，不会发送真实邮件。",
                EffectClass.NON_IDEMPOTENT_WRITE, true, 2048,
                ToolRegistry.schema(
                        new String[]{"to", "subject", "body"},
                        new ToolSpec.PropertySpec[]{
                                ToolSpec.PropertySpec.req("string", "收件人邮箱"),
                                ToolSpec.PropertySpec.req("string", "邮件主题"),
                                ToolSpec.PropertySpec.req("string", "邮件正文")
                        }));
    }

    @Override
    public ToolResult execute(Principal principal, Map<String, Object> arguments, ToolContext context) {
        String to = str(arguments.get("to"));
        String subject = str(arguments.get("subject"));
        String body = str(arguments.get("body"));

        if (!to.matches("[\\w.+-]+@[\\w-]+\\.[\\w.]+")) {
            return ToolResult.failed("INVALID_RECIPIENT", "收件人邮箱格式不正确");
        }
        if (subject.isBlank()) {
            return ToolResult.failed("EMPTY_SUBJECT", "邮件主题不能为空");
        }

        // 业务唯一键去重：相同 effectKey 已送达则直接返回原回执，不重复发送
        String receipt = delivered.get(context.effectKey());
        if (receipt != null) {
            log.info("send_email 幂等命中 effectKey={}，不重复发送", context.effectKey());
            return ToolResult.success("邮件已发送（幂等命中，未重复发送）：回执 " + receipt);
        }

        // 模拟外部调用（真实实现此处为 SMTP/API 调用；支持取消检查）
        if (context.control() != null && context.control().cancellationRequested()) {
            return ToolResult.failed("CANCELLED", "任务已取消，未发送");
        }

        String receiptId = "MAIL-" + Integer.toHexString((context.effectKey() + to).hashCode());
        delivered.putIfAbsent(context.effectKey(), receiptId);
        log.info("send_email 已发送 to={} subject={} receipt={} effectKey={}",
                to, subject, receiptId, context.effectKey());
        return ToolResult.success("邮件已发送。\n- 收件人：" + to + "\n- 主题：" + subject + "\n- 回执：" + receiptId);
    }

    /** 演示用：查询是否已送达（可用于核对流程）。 */
    public Set<String> deliveredKeys() {
        return Set.copyOf(delivered.keySet());
    }

    private String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
