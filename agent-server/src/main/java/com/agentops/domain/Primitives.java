package com.agentops.domain;

import com.agentops.domain.AgentDomain.Phase;
import com.agentops.domain.AgentDomain.RunStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 核心值对象（文档附录 35）。
 * Principal 只能由已验证的服务端状态构造，绝不能来自模型参数（文档 15.1）。
 */
public final class Primitives {

    private Primitives() {
    }

    /** 已验证主体。 */
    public record Principal(UUID tenantId, UUID userId) {
        public static Principal of(UUID tenantId, UUID userId) {
            return new Principal(tenantId, userId);
        }
    }

    /** 任务定位键。 */
    public record RunKey(Principal owner, UUID sessionId, UUID runId) {
    }

    /** Worker 租约：提交时必须校验 owner、worker、epoch、state_version 与有效租约。 */
    public record Lease(RunKey run, UUID workerId, long epoch, Instant expiresAt) {
        public boolean validAt(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    /**
     * 完成契约（文档 19.1）：任务开始时保存，模型不能在最后自行放宽。
     */
    public record CompletionContract(long contractVersion, String taskType,
                                     Map<String, Object> requirements,
                                     List<String> requiredArtifactKinds,
                                     boolean evidenceRequired,
                                     String notes) {
        public static CompletionContract defaults(String taskType) {
            return new CompletionContract(1, taskType, Map.of(), List.of(), false, "");
        }
    }

    /**
     * 工作记忆/检查点状态（文档 13.8 / 16.4）。
     * 保存结构化业务状态，无需持久化模型逐字内部推理。
     */
    public record WorkingState(String goal,
                               String strategy,
                               int planVersion,
                               List<PlanStep> planSteps,
                               String currentPlanStepId,
                               List<String> completedStepIds,
                               List<String> pendingStepIds,
                               List<String> evidenceRefs,
                               List<String> unresolvedItems,
                               int replanCount,
                               int repairCount,
                               String pendingToolBatchJson,
                               String lastModelRevisionId,
                               long lastMessageSeq) {

        public static WorkingState empty(String goal) {
            return new WorkingState(goal, "REACT", 1, List.of(), null,
                    List.of(), List.of(), List.of(), List.of(), 0, 0,
                    null, null, 0);
        }

        public WorkingState withToolBatch(String batchJson, String revisionId, long lastSeq) {
            return new WorkingState(goal, strategy, planVersion, planSteps, currentPlanStepId,
                    completedStepIds, pendingStepIds, evidenceRefs, unresolvedItems,
                    replanCount, repairCount, batchJson, revisionId, lastSeq);
        }

        public WorkingState withGoal(String nextGoal) {
            return new WorkingState(nextGoal,strategy,planVersion,planSteps,currentPlanStepId,completedStepIds,
                    pendingStepIds,evidenceRefs,unresolvedItems,replanCount,repairCount,pendingToolBatchJson,
                    lastModelRevisionId,lastMessageSeq);
        }

        public WorkingState withProgress(List<String> completed, List<String> pending, long lastSeq) {
            return new WorkingState(goal, strategy, planVersion, planSteps, currentPlanStepId,
                    completed, pending, evidenceRefs, unresolvedItems,
                    replanCount, repairCount, pendingToolBatchJson, lastModelRevisionId, lastSeq);
        }

        public WorkingState withRepair() {
            return new WorkingState(goal, strategy, planVersion, planSteps, currentPlanStepId,
                    completedStepIds, pendingStepIds, evidenceRefs, unresolvedItems,
                    replanCount, repairCount + 1, null, lastModelRevisionId, lastMessageSeq);
        }

        public WorkingState withPlan(List<PlanStep> steps, String currentId, int replanInc) {
            return new WorkingState(goal, strategy, planVersion + 1, steps, currentId,
                    completedStepIds, pendingStepIds, evidenceRefs, unresolvedItems,
                    replanCount + replanInc, repairCount, pendingToolBatchJson,
                    lastModelRevisionId, lastMessageSeq);
        }
    }

    /** 计划步骤（Plan-and-Execute 用，简短为宜）。 */
    public record PlanStep(String id, String title, String acceptance, String status) {
        public static PlanStep pending(String id, String title) {
            return new PlanStep(id, title, "", "pending");
        }
    }

    /** 版本快照：任务创建时固定，便于回归与定位质量变化（文档 23 / 31）。 */
    public record VersionSnapshot(String model, String promptVersion, String toolRegistryVersion,
                                  String policyVersion, String validatorVersion) {
    }

    /** 任务权威快照。 */
    public record RunSnapshot(RunKey key, RunStatus status, Phase phase,
                              long stateVersion, long checkpointVersion, long workerEpoch,
                              Instant deadlineAt, Instant cancelRequestedAt,
                              CompletionContract contract, WorkingState workingState,
                              VersionSnapshot versions, String questionId, String questionText,
                              int repairCount, RunMode mode, String approvedPlan, boolean pauseRequested) {

        public RunSnapshot {
            mode = mode == null ? RunMode.BUILD : mode;
            approvedPlan = approvedPlan == null ? "" : approvedPlan;
        }

        public RunSnapshot(RunKey key, RunStatus status, Phase phase,
                           long stateVersion, long checkpointVersion, long workerEpoch,
                           Instant deadlineAt, Instant cancelRequestedAt,
                           CompletionContract contract, WorkingState workingState,
                           VersionSnapshot versions, String questionId, String questionText, int repairCount,
                           RunMode mode, String approvedPlan) {
            this(key,status,phase,stateVersion,checkpointVersion,workerEpoch,deadlineAt,cancelRequestedAt,
                    contract,workingState,versions,questionId,questionText,repairCount,mode,approvedPlan,false);
        }

        public RunSnapshot(RunKey key, RunStatus status, Phase phase,
                           long stateVersion, long checkpointVersion, long workerEpoch,
                           Instant deadlineAt, Instant cancelRequestedAt,
                           CompletionContract contract, WorkingState workingState,
                           VersionSnapshot versions, String questionId, String questionText, int repairCount) {
            this(key, status, phase, stateVersion, checkpointVersion, workerEpoch, deadlineAt,
                    cancelRequestedAt, contract, workingState, versions, questionId, questionText,
                    repairCount, RunMode.BUILD, "");
        }

        public boolean cancelled() {
            return cancelRequestedAt != null;
        }

        public boolean expired(Instant now) {
            return now.isAfter(deadlineAt);
        }
    }
}
