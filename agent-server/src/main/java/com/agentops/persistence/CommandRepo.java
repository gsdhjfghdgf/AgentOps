package com.agentops.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 运行中命令与契约修订仓储（文档 29 节）。
 * 命令状态属于命令子系统，不新增 run 生命周期状态。
 */
@Repository
public class CommandRepo {

    private final JdbcTemplate jdbc;

    public CommandRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record CommandRow(UUID commandId, long commandSeq, String type, String payloadJson,
                             String status, String result, String rejectReason) {
    }

    public record BarrierRow(long commandSeq, String type, String payloadJson) {
    }

    /**
     * 接收命令（文档 29.2）：同一 run 协调事务登记命令并设置控制屏障。
     * 重复 commandId 返回原处理结果（幂等）。
     */
    public Optional<CommandRow> insertCommand(UUID tenantId, UUID userId, UUID sessionId, UUID runId,
                                              UUID commandId, String type, String payloadJson,
                                              UUID questionId, Long expectedStateVersion,
                                              Long expectedContractVersion) {
        var inserted = jdbc.query("""
                INSERT INTO run_commands (tenant_id, user_id, session_id, run_id, command_id,
                                          command_seq, type, payload, question_id,
                                          expected_state_version, expected_contract_version)
                VALUES (?,?,?,?,?, (SELECT COALESCE(MAX(command_seq),0)+1 FROM run_commands
                                    WHERE tenant_id=? AND run_id=?), ?,?::jsonb,?,?,?)
                ON CONFLICT (tenant_id, run_id, command_id) DO NOTHING
                RETURNING command_id, command_seq, type, payload::text, status, result, reject_reason
                """, commandMapper(), tenantId, userId, sessionId, runId, commandId,
                tenantId, runId, type, payloadJson, questionId, expectedStateVersion,
                expectedContractVersion);
        if (!inserted.isEmpty()) return inserted.stream().findFirst();

        // 幂等命中：返回已有命令（同键不同内容由上层 hash 校验）
        return findCommand(tenantId, runId, commandId);
    }

    public Optional<CommandRow> findCommand(UUID tenantId, UUID runId, UUID commandId) {
        var rows = jdbc.query("""
                SELECT command_id, command_seq, type, payload::text, status, result, reject_reason
                FROM run_commands WHERE tenant_id=? AND run_id=? AND command_id=?
                """, commandMapper(), tenantId, runId, commandId);
        return rows.stream().findFirst();
    }

    /** 控制屏障：未处理的关键命令（cancel/contract_change），Worker 在新行动及成功提交前检查（文档 29.2）。 */
    public List<BarrierRow> pendingBarrier(UUID tenantId, UUID runId) {
        return jdbc.query("""
                SELECT command_seq, type, payload::text
                FROM run_commands
                WHERE tenant_id=? AND run_id=? AND status='received'
                  AND type IN ('contract_change','cancel','steer')
                ORDER BY command_seq
                """, Jdbc.reader(rs -> new BarrierRow(rs.getLong("command_seq"),
                rs.getString("type"), rs.getString("payload"))), tenantId, runId);
    }

    public void applyCommand(UUID tenantId, UUID runId, UUID commandId, String result, Instant at) {
        jdbc.update("""
                UPDATE run_commands SET status='applied', result=?, handled_at=?
                WHERE tenant_id=? AND run_id=? AND command_id=? AND status='received'
                """, result, at, tenantId, runId, commandId);
    }

    public List<CommandRow> pendingSteering(UUID tenantId, UUID runId) {
        return jdbc.query("""
                SELECT command_id,command_seq,type,payload::text,status,result,reject_reason
                FROM run_commands WHERE tenant_id=? AND run_id=? AND type='steer' AND status='received'
                ORDER BY command_seq
                """, commandMapper(), tenantId, runId);
    }

    /** 按 seq 应用（引擎屏障处理：BarrierRow 只带 seq）。 */
    public void applyCommandBySeq(UUID tenantId, UUID runId, long commandSeq, String result, Instant at) {
        jdbc.update("""
                UPDATE run_commands SET status='applied', result=?, handled_at=?
                WHERE tenant_id=? AND run_id=? AND command_seq=? AND status='received'
                """, result, at, tenantId, runId, commandSeq);
    }

    public void rejectCommand(UUID tenantId, UUID runId, UUID commandId, String reason, Instant at) {
        jdbc.update("""
                UPDATE run_commands SET status='rejected', reject_reason=?, handled_at=?
                WHERE tenant_id=? AND run_id=? AND command_id=? AND status='received'
                """, reason, at, tenantId, runId, commandId);
    }

    public boolean isDuplicateCommand(UUID tenantId, UUID runId, UUID commandId, String payloadHash) {
        var existing = jdbc.query("""
                SELECT md5(payload::text) FROM run_commands
                WHERE tenant_id=? AND run_id=? AND command_id=?
                """, Jdbc.reader(rs -> rs.getString(1)), tenantId, runId, commandId);
        return !existing.isEmpty() && !existing.get(0).equals(payloadHash);
    }

    // ==================== 契约修订 ====================

    /** 保存新合同版本（文档 29.3）：旧候选与校验报告不再适用于新合同。 */
    public long insertContractRevision(UUID tenantId, UUID userId, UUID sessionId, UUID runId,
                                       String contractJson, String reason, UUID commandId) {
        var next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version),0)+1 FROM run_contract_revisions
                WHERE tenant_id=? AND run_id=?
                """, Long.class, tenantId, runId);
        long version = next == null ? 1 : next;
        jdbc.update("""
                INSERT INTO run_contract_revisions (tenant_id, user_id, session_id, run_id,
                                                    version, contract, reason, approved_by_command)
                VALUES (?,?,?,?,?,?::jsonb,?,?)
                """,
                tenantId, userId, sessionId, runId, version, contractJson, reason, commandId);
        return version;
    }

    public Optional<String> findLatestContract(UUID tenantId, UUID runId) {
        var rows = jdbc.query("""
                SELECT contract::text FROM run_contract_revisions
                WHERE tenant_id=? AND run_id=? ORDER BY version DESC LIMIT 1
                """, Jdbc.reader(rs -> rs.getString(1)), tenantId, runId);
        return rows.stream().findFirst();
    }

    public long latestContractVersion(UUID tenantId, UUID runId) {
        var v = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version),1) FROM run_contract_revisions
                WHERE tenant_id=? AND run_id=?
                """, Long.class, tenantId, runId);
        return v == null ? 1 : v;
    }

    private org.springframework.jdbc.core.RowMapper<CommandRow> commandMapper() {
        return Jdbc.reader(rs -> new CommandRow(Jdbc.uuid(rs, "command_id"), rs.getLong("command_seq"),
                rs.getString("type"), rs.getString("payload"), rs.getString("status"),
                rs.getString("result"), rs.getString("reject_reason")));
    }
}
