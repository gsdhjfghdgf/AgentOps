package com.agentops.budget;

import com.agentops.config.AgentProperties;
import com.agentops.domain.Primitives.Principal;
import com.agentops.persistence.BudgetRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 预算服务（文档 17 节）：
 * 执行前：used + reserved + 本次上界 <= limit 才允许；
 * 原子创建 reservation，增加 reserved 与尝试次数；
 * 明确用量后释放预留并结算实际使用，只结算一次；
 * 未知用量按上界保守结算，之后核对校正。
 */
@Service
public class BudgetService {

    private static final Logger log = LoggerFactory.getLogger(BudgetService.class);

    private final BudgetRepo repo;
    private final AgentProperties props;
    private final Clock clock;

    public BudgetService(BudgetRepo repo, AgentProperties props, Clock clock) {
        this.repo = repo;
        this.props = props;
        this.clock = clock;
    }

    /** run 级预算账户的周期键。 */
    public String runPeriodKey(UUID runId) {
        return "run:" + runId;
    }

    /** 用户月度预算周期键。 */
    public String monthPeriodKey() {
        return LocalDate.now(clock).toString().substring(0, 7);
    }

    /** 创建任务时初始化 run 预算账户（T1 同事务调用）。 */
    public void initRunAccount(Principal owner, UUID runId) {
        var b = props.budget();
        repo.ensureAccount(owner.tenantId(), owner.userId(), runPeriodKey(runId), "run",
                b.maxTotalTokens(), b.maxCostMicros(), b.maxModelAttempts(), b.maxToolAttempts());
    }

    /** 确保用户月度账户存在（幂等）。 */
    public void ensureUserMonthAccount(Principal owner) {
        var b = props.budget();
        repo.ensureAccount(owner.tenantId(), owner.userId(), monthPeriodKey(), "user_month",
                b.maxTotalTokens() * 30, b.maxCostMicros() * 30,
                b.maxModelAttempts() * 30, b.maxToolAttempts() * 30);
    }

    /**
     * 预留（T3 同事务调用）：尝试次数与 token/成本上界同时校验。
     *
     * @return 预留成功返回 true；预算不足返回 false（调用方转 BUDGET_EXCEEDED）
     */
    public boolean reserve(Principal owner, UUID sessionId, UUID runId, UUID attemptId,
                           StepAttempts attempts, long reserveTokens, long reserveCostMicros,
                           com.agentops.domain.AgentDomain.StepKind kind) {
        var b = props.budget();
        var runKey = runPeriodKey(runId);
        var account = repo.findAccount(owner.tenantId(), owner.userId(), runKey, "run");
        int modelLimit = account.map(BudgetRepo.AccountRow::maxModelAttempts).orElse(b.maxModelAttempts());
        int toolLimit = account.map(BudgetRepo.AccountRow::maxToolAttempts).orElse(b.maxToolAttempts());

        // 尝试次数上限（文档 17.1）
        if (kind == com.agentops.domain.AgentDomain.StepKind.MODEL && attempts.modelAttempts >= modelLimit) {
            log.warn("run={} 模型尝试次数超限 {}", runId, attempts.modelAttempts);
            return false;
        }
        if (kind == com.agentops.domain.AgentDomain.StepKind.TOOL && attempts.toolAttempts >= toolLimit) {
            log.warn("run={} 工具尝试次数超限 {}", runId, attempts.toolAttempts);
            return false;
        }

        // run 级 token 预留（原子条件更新）
        if (!repo.reserve(owner.tenantId(), owner.userId(), runKey, "run",
                reserveTokens, reserveCostMicros)) {
            log.warn("run={} 预算预留失败 tokens={} cost={}", runId, reserveTokens, reserveCostMicros);
            return false;
        }

        // 用户月度账户同步累计（宽松：失败不阻断 run，只记录）
        ensureUserMonthAccount(owner);
        repo.reserve(owner.tenantId(), owner.userId(), monthPeriodKey(), "user_month",
                reserveTokens, reserveCostMicros);

        repo.insertReservation(owner.tenantId(), owner.userId(), sessionId, runId, attemptId, runKey,
                reserveTokens, reserveCostMicros, "price-v1");
        return true;
    }

    public void extendRun(Principal owner, UUID runId) {
        var b=props.budget();
        if (!repo.extendRun(owner.tenantId(), owner.userId(), runPeriodKey(runId),
                b.maxTotalTokens(), b.maxModelAttempts(), b.maxToolAttempts()))
            throw new IllegalStateException("任务预算账户不存在");
    }

    /** 常用重载：预留成本按 0 处理（当前计费简化为按 token）。 */
    public void settle(Principal owner, UUID runId, UUID attemptId,
                       long reservedTokens, long actualTokens, boolean usageKnown) {
        settle(owner, runId, attemptId, reservedTokens, 0L, actualTokens, usageKnown);
    }

    /** 结算实际用量（T4 同事务调用，幂等）。 */
    public void settle(Principal owner, UUID runId, UUID attemptId,
                       long reservedTokens, long reservedCost,
                       long actualTokens, boolean usageKnown) {
        var runKey = runPeriodKey(runId);
        long actualCost = 0;
        if (repo.settleReservation(owner.tenantId(), attemptId, actualTokens, actualCost, usageKnown)) {
            repo.settle(owner.tenantId(), owner.userId(), runKey, "run",
                    reservedTokens, reservedCost, actualTokens, actualCost);
            repo.settle(owner.tenantId(), owner.userId(), monthPeriodKey(), "user_month",
                    reservedTokens, reservedCost, actualTokens, actualCost);
        }
        if (!usageKnown) {
            // usage 缺失：保守处理并可对账，不直接退还全部预留（文档 25）
            log.warn("run={} attempt={} usage 缺失，按保守上界结算 tokens={}", runId, attemptId, reservedTokens);
            repo.settleReservationConservative(owner.tenantId(), attemptId, reservedTokens);
        }
    }

    /** 释放预留（调用失败且确无用量）。 */
    public void release(Principal owner, UUID runId, UUID attemptId, long reservedTokens, long reservedCost) {
        if (repo.releaseReservation(owner.tenantId(), attemptId)) {
            repo.release(owner.tenantId(), owner.userId(), runPeriodKey(runId), "run",
                    reservedTokens, reservedCost);
            repo.release(owner.tenantId(), owner.userId(), monthPeriodKey(), "user_month",
                    reservedTokens, reservedCost);
        }
    }

    /** 读取预留 token 数（结算/失败处理用）。 */
    public long reservedTokens(java.util.UUID tenantId, java.util.UUID attemptId) {
        return repo.findReservedTokens(tenantId, attemptId).orElse(0L);
    }

    /** 读取预留（旧签名兼容）。 */
    public long findReservedTokens(java.util.UUID tenantId, java.util.UUID attemptId) {
        return reservedTokens(tenantId, attemptId);
    }

    /** 已发生的尝试次数（预留前由调用方读取）。 */
    public record StepAttempts(int modelAttempts, int toolAttempts) {
    }
}
