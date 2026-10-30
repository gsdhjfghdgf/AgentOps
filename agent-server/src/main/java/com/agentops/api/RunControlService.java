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
import java.util.*;

/** Durable user control at safe boundaries. Pausing never rolls back already committed work. */
@Service
public class RunControlService {
    private final RunRepo runs;
    private final StateRepo states;
    private final ExecutionRepo execution;
    private final MessageRepo messages;
    private final SessionRepo sessions;
    private final CommandRepo commands;
    private final RunEventService events;
    private final Json json;
    private final Clock clock;
    public RunControlService(RunRepo runs,StateRepo states,ExecutionRepo execution,MessageRepo messages,
            SessionRepo sessions,CommandRepo commands,RunEventService events,Json json,Clock clock) {
        this.runs=runs;this.states=states;this.execution=execution;this.messages=messages;this.sessions=sessions;
        this.commands=commands;this.events=events;this.json=json;this.clock=clock;
    }
    @Transactional
    public Map<String,Object> pause(Principal owner,UUID id,UUID request) {
        validate(request,"");
        var run=owned(owner,id);
        if(replay(owner,run,request,"pause",""))return response(run,true);
        if(run.status()==RunStatus.PAUSED || RunRepo.PAUSE_REQUESTED.equals(run.waitingReason())) {
            receipt(owner,run,request,"pause","");
            return response(run,true);
        }
        if(!Set.of(RunStatus.QUEUED,RunStatus.RUNNING,RunStatus.RETRY_WAIT).contains(run.status())
                || run.cancelRequestedAt()!=null || !run.deadlineAt().isAfter(Instant.now(clock)))
            throw new RunApplicationService.ConflictException("当前任务已结束或在等待补充/授权，无法请求暂停");
        boolean running=run.status()==RunStatus.RUNNING;
        boolean changed=running?runs.requestPause(owner,id,run.stateVersion()):runs.pauseIdle(owner,id,run.stateVersion());
        if(!changed)throw new RunApplicationService.ConflictException("任务状态已变化，请刷新后重试");
        receipt(owner,run,request,"pause","");
        String status=running?"pause_requested":"paused";
        events.append(owner,run.sessionId(),id,running?"run.pause_requested":"run.paused",Map.of("status",status));
        return Map.of("runId",id.toString(),"status",status,"idempotentHit",false);
    }
    @Transactional
    public Map<String,Object> resume(Principal owner,UUID id,UUID request,String supplement) {
        String text=supplement==null?"":supplement.trim();
        validate(request,text);
        var run=owned(owner,id);
        if(replay(owner,run,request,"resume",text))return response(run,true);
        if(run.status()!=RunStatus.PAUSED || run.cancelRequestedAt()!=null)
            throw new RunApplicationService.ConflictException("任务尚未暂停，请等待当前操作安全结束");
        var known=execution.listToolCalls(owner.tenantId(),id);
        if(known.stream().anyMatch(c->Set.of("unknown","executing").contains(c.status())))
            throw new RunApplicationService.ConflictException("仍有操作结果需要核对，不能直接继续以免重复执行");
        var working=states.findLatest(owner.tenantId(),id).map(c->json.read(c.stateJson(),WorkingState.class))
                .orElseThrow(()->new RunApplicationService.ConflictException("任务检查点不可用"));
        long seq=working.lastMessageSeq();
        var byCall=new HashMap<String,ExecutionRepo.ToolCallRow>();
        known.forEach(call->byCall.put(call.callId(),call));
        if(working.pendingToolBatchJson()!=null && !working.pendingToolBatchJson().isBlank()) {
            for(var call:json.tree(working.pendingToolBatchJson()).path("calls")) {
                String callId=call.path("id").asText();
                var prior=byCall.get(callId);
                if(prior!=null && !Set.of("recorded","approved").contains(prior.status()))continue;
                if(prior!=null) {
                    execution.updateToolCallStatus(owner.tenantId(),id,callId,"cancelled",null,"REPLAN_AFTER_PAUSE");
                    execution.updateStepStatus(owner.tenantId(),id,prior.stepId(),"cancelled");
                }
                seq=sessions.allocateSeq(owner,run.sessionId());
                messages.insert(new MessageRepo.MessageRow(owner.tenantId(),owner.userId(),run.sessionId(),seq,id,
                        MessageRole.TOOL,json.write(Map.of("content","此意图在执行前已撤销，未执行；请按暂停后最新要求重新决定下一步。")),
                        1,null,callId,Instant.now(clock)));
            }
        }
        if(!text.isBlank()) {
            seq=sessions.allocateSeq(owner,run.sessionId());
            messages.insert(new MessageRepo.MessageRow(owner.tenantId(),owner.userId(),run.sessionId(),seq,id,
                    MessageRole.USER,json.write(Map.of("content",text)),1,null,null,Instant.now(clock)));
            working=working.withGoal(working.goal()+"\n\n用户暂停后的最新补充：\n"+text);
        }
        var next=working.withToolBatch(null,null,seq);
        long checkpoint=states.insertCheckpoint(owner.tenantId(),owner.userId(),run.sessionId(),id,
                run.workerEpoch()+1,json.write(next));
        if(!runs.resumePaused(owner,id,run.stateVersion(),checkpoint))
            throw new RunApplicationService.ConflictException("任务状态已变化，请刷新后重试");
        receipt(owner,run,request,"resume",text);
        events.append(owner,run.sessionId(),id,"run.resumed",Map.of("status","queued","supplemented",!text.isBlank()));
        return Map.of("runId",id.toString(),"status","queued","idempotentHit",false);
    }
    private RunRepo.RunRow owned(Principal owner,UUID id) {
        return runs.lockByOwner(owner,id).orElseThrow(()->new RunApplicationService.NotFoundException("任务不存在"));
    }
    private void validate(UUID request,String text) {
        if(request==null || text.length()>8000)throw new RunApplicationService.BadRequestException("请提供请求编号，补充内容不能超过8000字");
    }
    private boolean replay(Principal owner,RunRepo.RunRow run,UUID request,String type,String text) {
        var prior=commands.findCommand(owner.tenantId(),run.id(),request);
        if(prior.isEmpty())return false;
        if(!type.equals(prior.get().type()) || !text.equals(json.tree(prior.get().payloadJson()).path("text").asText()))
            throw new RunApplicationService.ConflictException("请求编号已用于其他操作");
        return true;
    }
    private void receipt(Principal owner,RunRepo.RunRow run,UUID request,String type,String text) {
        commands.insertCommand(owner.tenantId(),owner.userId(),run.sessionId(),run.id(),request,type,
                json.write(Map.of("text",text)),null,run.stateVersion(),null);
        commands.applyCommand(owner.tenantId(),run.id(),request,"applied",Instant.now(clock));
    }
    private Map<String,Object> response(RunRepo.RunRow run,boolean replay) {
        return Map.of("runId",run.id().toString(),"status",
                RunRepo.PAUSE_REQUESTED.equals(run.waitingReason())?"pause_requested":run.status().db(),"idempotentHit",replay);
    }
}
