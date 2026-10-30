package com.agentops.model;

import com.agentops.domain.ModelDecision;
import com.agentops.domain.Primitives.RunSnapshot;
import com.agentops.domain.ToolTypes.ToolSpec;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * 模型端口（文档 13.1 / 附录 35）：
 * 每次只执行一个模型请求，不在内部自动执行工具；
 * 工具转录、追踪、重试和恢复由应用负责。
 */
public interface ModelPort {
    record Response(ModelDecision decision, com.agentops.domain.ResultTypes.Usage usage) {}
    class Cancelled extends RuntimeException {public Cancelled(){super("任务已停止");}}

    default Response request(RunSnapshot snapshot,String systemPrompt,List<ModelMessage> messages,
                             List<ToolSpec> allowedTools,ExecutionControl control,Consumer<String> provisional,Consumer<String> commentary){
        return request(snapshot,systemPrompt,messages,allowedTools,control,provisional);
    }

    default Response request(RunSnapshot snapshot, String systemPrompt, List<ModelMessage> messages,
                             List<ToolSpec> allowedTools, ExecutionControl control, Consumer<String> provisional) {
        return new Response(next(snapshot, systemPrompt, messages, allowedTools, control, provisional), null);
    }

    /**
     * 一次模型请求。
     *
     * @param snapshot       任务快照（含上下文组装所需的运行状态）
     * @param allowedTools   本次可见工具集合（PolicyService 已筛选）
     * @param control        取消与截止时间控制
     * @param systemPrompt   系统规则（含 Skill、记忆、摘要等上下文）
     * @param messages       模型上下文消息（完整尾部 + 摘要）
     * @param provisional    正文增量回调（用于前端 message.delta 展示；不进入持久化正文）
     */
    ModelDecision next(RunSnapshot snapshot, String systemPrompt, List<ModelMessage> messages,
                       List<ToolSpec> allowedTools, ExecutionControl control,
                       Consumer<String> provisional);

    /** 模型消息（与供应商协议无关的规范化表示）。 */
    record ModelMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {

        public static ModelMessage system(String content) {
            return new ModelMessage("system", content, List.of(), null);
        }

        public static ModelMessage user(String content) {
            return new ModelMessage("user", content, List.of(), null);
        }

        public static ModelMessage assistant(String content) {
            return new ModelMessage("assistant", content, List.of(), null);
        }

        public static ModelMessage assistantWithCalls(String content, List<ToolCall> calls) {
            return new ModelMessage("assistant", content, calls, null);
        }

        public static ModelMessage toolResult(String toolCallId, String content) {
            return new ModelMessage("tool", content, List.of(), toolCallId);
        }
    }

    record ToolCall(String id, String name, String argumentsJson) {
    }

    /** 协作式取消：外部副作用可能已发生，取消只阻止新行动（文档 16.5）。 */
    interface ExecutionControl {
        boolean cancellationRequested();

        Instant deadlineAt();

        default boolean exceeded(Instant now) {
            return now.isAfter(deadlineAt());
        }
    }
}
