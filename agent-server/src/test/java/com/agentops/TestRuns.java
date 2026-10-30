package com.agentops;

import com.agentops.domain.AgentDomain.Phase;
import com.agentops.domain.AgentDomain.RunStatus;
import com.agentops.domain.Primitives.Principal;
import com.agentops.persistence.RunRepo.RunRow;
import java.time.Instant;
import java.util.UUID;

public final class TestRuns {
    public static final Principal OWNER = Principal.of(new UUID(0, 1), new UUID(0, 2));
    public static final UUID SESSION = new UUID(0, 3);
    public static final UUID RUN = new UUID(0, 4);
    public static final UUID QUESTION = new UUID(0, 5);
    public static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");
    private TestRuns() {}

    public static RunRow row(RunStatus status, long eventSeq, String hash) {
        return build(status,eventSeq,hash,null,Phase.MODEL);
    }
    public static RunRow leasedRow(Phase phase, UUID worker) {
        return build(RunStatus.RUNNING,1,"hash",worker,phase);
    }
    private static RunRow build(RunStatus status,long eventSeq,String hash,UUID worker,Phase phase) {
        return build(status,eventSeq,hash,worker,phase,"{}");
    }
    public static RunRow rowWithConfig(String config){return build(RunStatus.RUNNING,1,"hash",null,Phase.MODEL,config);}
    private static RunRow build(RunStatus status,long eventSeq,String hash,UUID worker,Phase phase,String config) {
        return new RunRow(OWNER.tenantId(), OWNER.userId(), SESSION, RUN, "key", hash,
                status, phase, 7, 1, worker, 1, worker==null?null:NOW.plusSeconds(60), NOW.plusSeconds(1800), null,
                NOW, eventSeq, null, null, QUESTION, "查询哪个时间段？", config, "{}",
                0, 0, 100, NOW, NOW, status.terminal() ? NOW.plusSeconds(2) : null);
    }
}
