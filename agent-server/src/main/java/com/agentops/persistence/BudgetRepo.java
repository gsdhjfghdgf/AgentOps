package com.agentops.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 预算仓储（文档 8.1 / 17.2 预留与结算）。
 * 预留是原子条件更新：used + reserved + 本次上界 <= limit 才允许（文档 17.2）。
 */
@Repository
public class BudgetRepo {

    private final JdbcTemplate jdbc;

    public BudgetRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record AccountRow(UUID tenantId, UUID userId, String periodKey, String budgetType,
                             long maxTotalTokens, long maxCostMicros, int maxModelAttempts,
                             int maxToolAttempts, long usedTokens, long reservedTokens) {
    }

    public boolean extendRun(UUID tenantId, UUID userId, String key, long tokens, int models, int tools) {
        return jdbc.update("""
                UPDATE budget_accounts SET max_total_tokens=GREATEST(max_total_tokens,used_tokens+reserved_tokens)+?,
                max_model_attempts=max_model_attempts+?, max_tool_attempts=max_tool_attempts+?, updated_at=now()
                WHERE tenant_id=? AND user_id=? AND period_key=? AND budget_type='run'
                """, tokens, models, tools, tenantId, userId, key) > 0;
    }

    /** 确保账户存在（run 级账户在创建任务时写入）。 */
    public void ensureAccount(UUID tenantId, UUID userId, String periodKey, String budgetType,
                              long maxTotalTokens, long maxCostMicros,
                              int maxModelAttempts, int maxToolAttempts) {
        jdbc.update("""
                INSERT INTO budget_accounts (tenant_id, user_id, period_key, budget_type,
                                             max_total_tokens, max_cost_micros,
                                             max_model_attempts, max_tool_attempts)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT (tenant_id, user_id, period_key, budget_type) DO NOTHING
                """, tenantId, userId, periodKey, budgetType, maxTotalTokens, maxCostMicros,
                maxModelAttempts, maxToolAttempts);
    }

    public Optional<AccountRow> findAccount(UUID tenantId, UUID userId, String periodKey, String budgetType) {
        var rows = jdbc.query("""
                SELECT tenant_id, user_id, period_key, budget_type, max_total_tokens,
                       max_cost_micros, max_model_attempts, max_tool_attempts,
                       used_tokens, reserved_tokens
                FROM budget_accounts
                WHERE tenant_id=? AND user_id=? AND period_key=? AND budget_type=?
                """, Jdbc.reader(rs -> new AccountRow(Jdbc.uuid(rs, "tenant_id"),
                Jdbc.uuid(rs, "user_id"), rs.getString("period_key"), rs.getString("budget_type"),
                rs.getLong("max_total_tokens"), rs.getLong("max_cost_micros"),
                rs.getInt("max_model_attempts"), rs.getInt("max_tool_attempts"),
                rs.getLong("used_tokens"), rs.getLong("reserved_tokens"))),
                tenantId, userId, periodKey, budgetType);
        return rows.stream().findFirst();
    }

    /**
     * 原子预留（文档 17.2）：
     * used + reserved + 本次上界 <= limit 时预留成功，否则返回 false（BUDGET_EXCEEDED）。
     */
    public boolean reserve(UUID tenantId, UUID userId, String periodKey, String budgetType,
                           long reserveTokens, long reserveCostMicros) {
        return jdbc.update("""
                UPDATE budget_accounts
                SET reserved_tokens = reserved_tokens + ?, reserved_cost_micros = reserved_cost_micros + ?,
                    updated_at = now()
                WHERE tenant_id=? AND user_id=? AND period_key=? AND budget_type=?
                  AND used_tokens + reserved_tokens + ? <= max_total_tokens
                  AND used_cost_micros + reserved_cost_micros + ? <= max_cost_micros
                """, reserveTokens, reserveCostMicros, tenantId, userId, periodKey, budgetType,
                reserveTokens, reserveCostMicros) > 0;
    }

    /** 结算：释放预留并累加实际用量，只结算一次（attempt 唯一）。 */
    public void settle(UUID tenantId, UUID userId, String periodKey, String budgetType,
                       long reservedTokens, long reservedCost, long actualTokens, long actualCost) {
        jdbc.update("""
                UPDATE budget_accounts
                SET reserved_tokens = GREATEST(0, reserved_tokens - ?),
                    reserved_cost_micros = GREATEST(0, reserved_cost_micros - ?),
                    used_tokens = used_tokens + ?, used_cost_micros = used_cost_micros + ?,
                    updated_at = now()
                WHERE tenant_id=? AND user_id=? AND period_key=? AND budget_type=?
                """, reservedTokens, reservedCost, actualTokens, actualCost,
                tenantId, userId, periodKey, budgetType);
    }

    /** 释放预留（调用失败且无用量）。 */
    public void release(UUID tenantId, UUID userId, String periodKey, String budgetType,
                        long reservedTokens, long reservedCost) {
        settle(tenantId, userId, periodKey, budgetType, reservedTokens, reservedCost, 0, 0);
    }

    // ==================== reservations（结算幂等） ====================

    public void insertReservation(UUID tenantId, UUID userId, UUID sessionId, UUID runId,
                                  UUID attemptId, String periodKey,
                                  long reservedTokens, long reservedCost, String priceVersion) {
        jdbc.update("""
                INSERT INTO budget_reservations (tenant_id, user_id, session_id, run_id, attempt_id,
                                                 account_period_key, reserved_tokens, reserved_cost_micros,
                                                 price_version, status)
                VALUES (?,?,?,?,?,?,?,?,?, 'reserved')
                ON CONFLICT (tenant_id, attempt_id) DO NOTHING
                """, tenantId, userId, sessionId, runId, attemptId, periodKey,
                reservedTokens, reservedCost, priceVersion);
    }

    /** 结算幂等：只对 status='reserved' 的预留结算一次。 */
    public boolean settleReservation(UUID tenantId, UUID attemptId, long actualTokens,
                                     long actualCost, boolean usageKnown) {
        return jdbc.update("""
                UPDATE budget_reservations
                SET actual_tokens=?, actual_cost_micros=?, usage_known=?, status='settled', settled_at=now()
                WHERE tenant_id=? AND attempt_id=? AND status='reserved'
                """, actualTokens, actualCost, usageKnown, tenantId, attemptId) > 0;
    }

    public boolean releaseReservation(UUID tenantId, UUID attemptId) {
        return jdbc.update("""
                UPDATE budget_reservations
                SET status='released', settled_at=now()
                WHERE tenant_id=? AND attempt_id=? AND status='reserved'
                """, tenantId, attemptId) > 0;
    }

    /** 未知用量：保留或按上界保守结算（文档 17.2 / 25 usage 缺失场景）。 */
    public boolean settleReservationConservative(UUID tenantId, UUID attemptId, long upperBoundTokens) {
        return jdbc.update("""
                UPDATE budget_reservations
                SET actual_tokens=GREATEST(actual_tokens, ?), usage_known=false, status='settled', settled_at=now()
                WHERE tenant_id=? AND attempt_id=? AND status='reserved'
                """, upperBoundTokens, tenantId, attemptId) > 0;
    }

    public Optional<Long> findReservedTokens(UUID tenantId, UUID attemptId) {
        var rows = jdbc.query("""
                SELECT reserved_tokens FROM budget_reservations
                WHERE tenant_id=? AND attempt_id=?
                """, Jdbc.reader(rs -> rs.getLong(1)), tenantId, attemptId);
        return rows.stream().findFirst();
    }
}
