package com.agentops.domain;

import com.agentops.domain.ToolTypes.ToolCallRequest;

import java.util.List;
import java.util.UUID;

/**
 * 模型单次请求的决策结果（文档 13.2 / 附录 35）。
 * ModelPort 每次只执行一个模型请求，不在内部自动执行工具。
 */
public sealed interface ModelDecision {

    /** 工具批次：先保存完整 assistant 调用消息及整个批次，再执行第一个工具（文档 13.2）。 */
    record ToolBatch(String assistantText, List<ToolCallRequest> calls) implements ModelDecision {
    }

    /** 候选答案：进入 VALIDATE。 */
    record FinalCandidate(ResultTypes.CandidateAnswer answer) implements ModelDecision {
    }

    /** 缺少信息：进入 WAITING_USER，释放 worker。 */
    record AskUser(UUID questionId, String question,List<String> options) implements ModelDecision {
        public AskUser(UUID questionId,String question){this(questionId,question,List.of());}
        public AskUser { options=options==null?List.of():List.copyOf(options); }
    }
}
