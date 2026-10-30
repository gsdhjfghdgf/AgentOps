package com.agentops.domain;

import java.util.Set;

/**
 * 领域枚举与状态机结构边检查（文档附录 35 / 14 节）。
 *
 * allowedTransition 仅检查结构边；业务授权、截止时间、版本与租约
 * 仍由 RunCoordinator 在事务内检查。
 */
public final class AgentDomain {

    private AgentDomain() {
    }

    /** 任务生命周期状态（文档 14.1）。 */
    public enum RunStatus {
        QUEUED, RUNNING, PAUSED, WAITING_USER, WAITING_APPROVAL, WAITING_RECONCILE,
        RETRY_WAIT, CANCEL_REQUESTED, SUCCEEDED, FAILED, CANCELLED,
        TIMED_OUT, BUDGET_EXCEEDED;

        public boolean terminal() {
            return switch (this) {
                case SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, BUDGET_EXCEEDED -> true;
                default -> false;
            };
        }

        public static RunStatus fromDb(String v) {
            return valueOf(v.trim().toUpperCase());
        }

        public String db() {
            return name().toLowerCase();
        }
    }

    /** RUNNING 内部执行阶段（文档 14.2）。 */
    public enum Phase {
        PREPARE, MODEL, TOOLS, VALIDATE, FINALIZE;

        public static Phase fromDb(String v) {
            return valueOf(v.trim().toUpperCase());
        }

        public String db() {
            return name().toLowerCase();
        }
    }

    /** 逻辑步骤类型。 */
    public enum StepKind {MODEL, TOOL, VALIDATE}

    /** 副作用类别：由开发者注册声明，不由模型填写（文档 11.1）。 */
    public enum EffectClass {READ_ONLY, IDEMPOTENT_WRITE, NON_IDEMPOTENT_WRITE}

    /** 外部调用结果状态：未知 ≠ 未执行（文档 1.3.5）。 */
    public enum ToolOutcomeStatus {SUCCEEDED, FAILED, UNKNOWN}

    /** 策略授权结论（文档 15）。 */
    public enum PolicyVerdict {ALLOW, DENY, REQUIRE_APPROVAL}

    /** 校验结论（文档 19.3）。 */
    public enum ValidationVerdict {PASS, REPAIRABLE, NEED_USER_INPUT, FATAL}

    /** 单次 tick 结果。 */
    public enum TickResult {CONTINUE, YIELDED, FINISHED, LEASE_LOST}

    /** 消息角色。 */
    public enum MessageRole {
        USER, ASSISTANT, SYSTEM, TOOL;

        public static MessageRole fromDb(String v) {
            return valueOf(v.trim().toUpperCase());
        }

        public String db() {
            return name().toLowerCase();
        }
    }

    /**
     * 结构状态转移检查（文档附录 35）。
     * 终态不可再进入；TIMED_OUT 可从任何非终态到达（截止时间约束）。
     */
    public static boolean allowedTransition(RunStatus from, RunStatus to) {
        if (from == to || from.terminal()) return false;
        if (to == RunStatus.TIMED_OUT) return true;
        return switch (from) {
            case QUEUED -> Set.of(RunStatus.RUNNING, RunStatus.CANCELLED,
                    RunStatus.PAUSED, RunStatus.BUDGET_EXCEEDED, RunStatus.FAILED).contains(to);
            case RUNNING -> Set.of(RunStatus.WAITING_USER, RunStatus.WAITING_APPROVAL,
                    RunStatus.WAITING_RECONCILE, RunStatus.RETRY_WAIT,
                    RunStatus.CANCEL_REQUESTED, RunStatus.SUCCEEDED,
                    RunStatus.PAUSED, RunStatus.FAILED, RunStatus.BUDGET_EXCEEDED).contains(to);
            case PAUSED -> Set.of(RunStatus.QUEUED,RunStatus.CANCELLED).contains(to);
            case WAITING_USER, WAITING_APPROVAL -> Set.of(RunStatus.QUEUED,
                    RunStatus.CANCELLED, RunStatus.FAILED).contains(to);
            case WAITING_RECONCILE -> Set.of(RunStatus.QUEUED, RunStatus.CANCELLED,
                    RunStatus.FAILED, RunStatus.BUDGET_EXCEEDED).contains(to);
            case RETRY_WAIT -> Set.of(RunStatus.QUEUED, RunStatus.CANCELLED,
                    RunStatus.PAUSED, RunStatus.BUDGET_EXCEEDED, RunStatus.FAILED).contains(to);
            case CANCEL_REQUESTED -> Set.of(RunStatus.CANCELLED,
                    RunStatus.WAITING_RECONCILE, RunStatus.FAILED).contains(to);
            default -> false;
        };
    }
}
