package com.agentops.api;

import com.agentops.domain.AgentDomain.*;
import com.agentops.domain.Primitives.*;
import com.agentops.event.RunEventService;
import com.agentops.infra.Json;
import com.agentops.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Durable user input, applied only after a complete tool batch and before success. */
@Service
public class SteeringService {
    private final RunRepo runs;
    private final SessionRepo sessions;
    private final MessageRepo messages;
    private final CommandRepo commands;
    private final RunEventService events;
    private final Json json;
    private final Clock clock;
    public SteeringService(RunRepo runs, SessionRepo sessions, MessageRepo messages, CommandRepo commands,
                           RunEventService events, Json json, Clock clock) {
        this.runs=runs; this.sessions=sessions; this.messages=messages; this.commands=commands;
        this.events=events; this.json=json; this.clock=clock;
    }

    @Transactional
    public Map<String,Object> submit(Principal owner, UUID runId, UUID commandId, String text) {
        if (commandId==null || text==null || text.isBlank() || text.length()>8000)
            throw new RunApplicationService.BadRequestException("请提供命令编号和 1～8000 字的补充要求");
        var run = runs.lockByOwner(owner,runId).orElseThrow(() -> new RunApplicationService.NotFoundException("任务不存在"));
        var prior=commands.findCommand(owner.tenantId(),runId,commandId);
        if (prior.isPresent()) {
            if (!"steer".equals(prior.get().type()) || !json.tree(prior.get().payloadJson()).path("text").asText().equals(text))
                throw new RunApplicationService.ConflictException("命令编号已被不同内容使用");
            return Map.of("commandId",commandId.toString(),"status",prior.get().status());
        }
        if (!Set.of(RunStatus.QUEUED,RunStatus.RUNNING,RunStatus.RETRY_WAIT).contains(run.status())
                || run.cancelRequestedAt()!=null || !run.deadlineAt().isAfter(Instant.now(clock)))
            throw new RunApplicationService.ConflictException("当前任务不可补充；等待问题请使用补充回答，已结束请发送新任务");
        commands.insertCommand(owner.tenantId(),owner.userId(),run.sessionId(),runId,commandId,"steer",
                json.write(Map.of("text",text)),null,run.stateVersion(),null);
        events.append(owner,run.sessionId(),runId,"run.command_received",
                Map.of("commandId",commandId.toString(),"text",text,"status","received"));
        return Map.of("commandId",commandId.toString(),"status","received");
    }

    @Transactional
    public boolean applyAtBoundary(Lease lease, RunSnapshot snapshot) {
        if (snapshot.phase()==Phase.TOOLS) return false;
        var owner=lease.run().owner();
        var run=runs.lockByOwner(owner,lease.run().runId()).orElse(null);
        var now=Instant.now(clock);
        if (run==null || run.status()!=RunStatus.RUNNING || run.stateVersion()!=snapshot.stateVersion()
                || run.workerEpoch()!=lease.epoch() || !lease.workerId().equals(run.workerId())
                || run.leaseUntil()==null || !run.leaseUntil().isAfter(now)
                || run.cancelRequestedAt()!=null || !run.deadlineAt().isAfter(now)) return false;
        var pending=commands.pendingSteering(owner.tenantId(),run.id());
        if (pending.isEmpty()) return false;
        for (var command:pending) {
            long seq=sessions.allocateSeq(owner,run.sessionId());
            messages.insert(new MessageRepo.MessageRow(owner.tenantId(),owner.userId(),run.sessionId(),seq,
                    run.id(),MessageRole.USER,json.write(Map.of("content",json.tree(command.payloadJson()).path("text").asText())),
                    1,null,null,now));
            commands.applyCommand(owner.tenantId(),run.id(),command.commandId(),"applied",now);
            events.append(owner,run.sessionId(),run.id(),"run.command_applied",
                    Map.of("commandId",command.commandId().toString(),"seq",seq,"status","applied"));
        }
        if (!runs.advancePhase(lease,snapshot.stateVersion(),Phase.MODEL)) throw new IllegalStateException("FENCING_ROLLBACK");
        events.append(owner,run.sessionId(),run.id(),"run.phase",Map.of("phase","model"));
        return true;
    }
}
