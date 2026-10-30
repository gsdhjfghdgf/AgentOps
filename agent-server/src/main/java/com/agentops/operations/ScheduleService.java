package com.agentops.operations;

import com.agentops.api.RunApplicationService;
import com.agentops.domain.Primitives.Principal;
import com.agentops.persistence.Jdbc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

@Service
public class ScheduleService {
    private final JdbcTemplate db;private final RunApplicationService app;private final OperationsService operations;private final TransactionTemplate tx;
    public ScheduleService(JdbcTemplate db,RunApplicationService app,OperationsService operations,TransactionTemplate tx){this.db=db;this.app=app;this.operations=operations;this.tx=tx;}
    public record Input(String name,String prompt,int intervalMinutes,Instant nextAt,UUID projectId){}
    public UUID create(Principal p,Input in){
        if(in.name()==null||in.name().isBlank()||in.name().length()>100||in.prompt()==null||in.prompt().isBlank()||in.prompt().length()>8000||in.intervalMinutes()<5||in.intervalMinutes()>525600||in.nextAt()==null)throw new IllegalArgumentException("名称、任务内容、首次时间和5分钟以上的间隔必填");
        return tx.execute(s->{var session=app.createSession(p,"定时 · "+in.name(),in.projectId());UUID id=UUID.randomUUID();db.update("INSERT INTO task_schedules(id,tenant_id,user_id,session_id,name,prompt,interval_minutes,next_at)VALUES(?,?,?,?,?,?,?,?)",id,p.tenantId(),p.userId(),UUID.fromString(session.id()),in.name(),in.prompt(),in.intervalMinutes(),in.nextAt());return id;});
    }
    public List<Map<String,Object>> list(Principal p){return db.queryForList("SELECT id,name,prompt,session_id,interval_minutes,next_at,enabled FROM task_schedules WHERE tenant_id=? AND user_id=? ORDER BY created_at DESC",p.tenantId(),p.userId());}
    public void enable(Principal p,UUID id,boolean enabled){if(db.update("UPDATE task_schedules SET enabled=? WHERE tenant_id=? AND user_id=? AND id=?",enabled,p.tenantId(),p.userId(),id)!=1)throw new RunApplicationService.NotFoundException("定时任务不存在");if(!enabled)db.update("UPDATE schedule_fires SET status='cancelled' WHERE schedule_id=? AND tenant_id=? AND user_id=? AND status='pending'",id,p.tenantId(),p.userId());}
    public void delete(Principal p,UUID id){enable(p,id,false);db.update("DELETE FROM task_schedules WHERE tenant_id=? AND user_id=? AND id=?",p.tenantId(),p.userId(),id);}
    @Scheduled(fixedDelay=15000,initialDelay=15000) public void tick(){
        // Slot creation and advancement are committed together. Missed intervals coalesce to one run.
        tx.executeWithoutResult(s->{var due=db.queryForList("SELECT * FROM task_schedules WHERE enabled AND next_at<=now() ORDER BY next_at LIMIT 10 FOR UPDATE SKIP LOCKED");for(var row:due){
            db.update("INSERT INTO schedule_fires(schedule_id,slot,tenant_id,user_id,session_id)VALUES(?,?,?,?,?)ON CONFLICT DO NOTHING",row.get("id"),row.get("next_at"),row.get("tenant_id"),row.get("user_id"),row.get("session_id"));
            db.update("UPDATE task_schedules SET next_at=now()+interval_minutes*interval '1 minute' WHERE id=?",row.get("id"));
        }});
        for(int i=0;i<10;i++){
            final Map<String,Object>[] claimed=new Map[]{null};
            try{
                Boolean processed=tx.execute(s->{
                    var rows=db.queryForList("SELECT f.*,j.prompt,j.name FROM schedule_fires f JOIN task_schedules j ON j.id=f.schedule_id AND j.enabled WHERE f.status='pending' AND f.next_attempt_at<=now() ORDER BY f.slot LIMIT 1 FOR UPDATE OF f SKIP LOCKED");
                    if(rows.isEmpty())return false;var row=rows.get(0);claimed[0]=row;
                    var owner=Principal.of((UUID)row.get("tenant_id"),(UUID)row.get("user_id"));
                    String key="schedule:"+row.get("schedule_id")+":"+row.get("slot");
                    var run=app.createRun(owner,(UUID)row.get("session_id"),new RunApplicationService.CreateRunRequest(key,row.get("prompt").toString(),"scheduled",null,null,null));
                    db.update("UPDATE schedule_fires SET status='created',run_id=? WHERE schedule_id=? AND slot=?",UUID.fromString(run.runId()),row.get("schedule_id"),row.get("slot"));
                    return true;
                });
                if(!Boolean.TRUE.equals(processed))break;
            }catch(RuntimeException error){
                // The nested run transaction may roll back. Update backoff only after that rollback.
                var row=claimed[0];if(row==null)throw error;
                if(error instanceof RunApplicationService.ConflictException||error instanceof com.agentops.coordinator.RunCoordinator.BusyException){
                    db.update("UPDATE schedule_fires SET attempts=attempts+1,next_attempt_at=now()+interval '30 seconds' WHERE schedule_id=? AND slot=? AND status='pending'",row.get("schedule_id"),row.get("slot"));
                }else{
                    db.update("UPDATE schedule_fires SET status='failed',last_error=? WHERE schedule_id=? AND slot=? AND status='pending'",error.getClass().getSimpleName(),row.get("schedule_id"),row.get("slot"));
                    var owner=Principal.of((UUID)row.get("tenant_id"),(UUID)row.get("user_id"));
                    operations.notify(owner,"schedule-start-failed:"+row.get("schedule_id")+":"+row.get("slot"),"定时任务启动失败："+row.get("name"),null);
                }
            }
        }
        var notices=db.query("""
            SELECT r.tenant_id,r.user_id,r.id,r.status,j.name FROM schedule_fires f JOIN runs r ON r.tenant_id=f.tenant_id AND r.id=f.run_id
            JOIN task_schedules j ON j.id=f.schedule_id WHERE r.status IN('succeeded','failed','cancelled','timed_out','budget_exceeded','waiting_approval','waiting_user','waiting_reconcile')
            AND NOT EXISTS(SELECT 1 FROM user_notifications n WHERE n.tenant_id=r.tenant_id AND n.user_id=r.user_id AND n.event_key='scheduled:'||r.id::text||':'||r.status) LIMIT 100
            """,Jdbc.reader(r->new Object[]{Principal.of(Jdbc.uuid(r,"tenant_id"),Jdbc.uuid(r,"user_id")),Jdbc.uuid(r,"id"),r.getString("status"),r.getString("name")}));
        for(var row:notices)operations.notify((Principal)row[0],"scheduled:"+row[1]+":"+row[2],row[3]+" · "+row[2],(UUID)row[1]);
        for(var row:db.queryForList("SELECT r.tenant_id,r.user_id,r.id FROM runs r JOIN usage_preferences u ON u.tenant_id=r.tenant_id AND u.user_id=r.user_id WHERE r.token_used>=u.reminder_tokens AND NOT EXISTS(SELECT 1 FROM user_notifications n WHERE n.tenant_id=r.tenant_id AND n.user_id=r.user_id AND n.event_key='usage:'||r.id::text) LIMIT 100"))operations.notify(Principal.of((UUID)row.get("tenant_id"),(UUID)row.get("user_id")),"usage:"+row.get("id"),"任务已达到Token提醒额度",(UUID)row.get("id"));
    }
}
