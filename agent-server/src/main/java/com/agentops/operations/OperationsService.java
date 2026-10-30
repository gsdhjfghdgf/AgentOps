package com.agentops.operations;

import com.agentops.api.RunApplicationService;
import com.agentops.domain.Primitives.Principal;
import com.agentops.infra.Json;
import com.agentops.persistence.RunRepo;
import com.agentops.project.ProjectService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class OperationsService {
    private final JdbcTemplate db;
    private final RunRepo runs;
    private final Json json;
    private final ProjectService projects;
    public OperationsService(JdbcTemplate db,RunRepo runs,Json json,ProjectService projects){this.db=db;this.runs=runs;this.json=json;this.projects=projects;}
    public RunRepo.RunRow owned(Principal p,UUID run){return runs.findByIdOwner(p,run).orElseThrow(()->new RunApplicationService.NotFoundException("任务不存在"));}
    public Map<String,Object> plan(Principal p,UUID run){
        owned(p,run);var rows=db.queryForList("SELECT revision,steps::text,updated_at FROM task_plans WHERE tenant_id=? AND user_id=? AND run_id=?",p.tenantId(),p.userId(),run);
        if(rows.isEmpty())return Map.of("revision",0,"steps",List.of());
        var row=rows.get(0);return Map.of("revision",row.get("revision"),"steps",json.list(row.get("steps").toString()));
    }
    @Transactional public Map<String,Object> savePlan(Principal p,UUID run,long revision,String stepsJson){
        var task=owned(p,run);if(task.status().terminal())throw new RunApplicationService.ConflictException("任务已结束，请重试步骤或创建新任务");
        var steps=json.tree(stepsJson);if(!steps.isArray()||steps.isEmpty()||steps.size()>30)throw new IllegalArgumentException("计划需要1～30个步骤");
        var previous=json.tree(json.write(plan(p,run).get("steps")));
        for(var old:previous)if(Set.of("done","doing").contains(old.path("status").asText())){
            var replacement=java.util.stream.StreamSupport.stream(steps.spliterator(),false).filter(s->s.path("id").asText().equals(old.path("id").asText())).findFirst().orElseThrow(()->new IllegalArgumentException("不能删除已完成或执行中的步骤"));
            if(!replacement.path("title").asText().equals(old.path("title").asText())||old.path("status").asText().equals("done")&&!replacement.path("status").asText().equals("done"))throw new IllegalArgumentException("只能调整后续待执行步骤，不能改写已完成内容");
        }
        var ids=new HashSet<String>();int doing=0;
        for(var step:steps){String id=step.path("id").asText(),title=step.path("title").asText(),status=step.path("status").asText();
            if(!id.matches("[a-zA-Z0-9_-]{1,50}")||!ids.add(id)||title.isBlank()||title.length()>300||!Set.of("pending","doing","done","failed","skipped").contains(status))throw new IllegalArgumentException("步骤ID须唯一，状态或标题无效");
            if(status.equals("doing"))doing++;
        }if(doing>1)throw new IllegalArgumentException("同时只能有一个执行中步骤");
        int changed=revision==0?db.update("INSERT INTO task_plans(tenant_id,user_id,run_id,steps)VALUES(?,?,?,?::jsonb) ON CONFLICT DO NOTHING",p.tenantId(),p.userId(),run,steps.toString()):db.update("UPDATE task_plans SET steps=?::jsonb,revision=revision+1,updated_at=now() WHERE tenant_id=? AND user_id=? AND run_id=? AND revision=?",steps.toString(),p.tenantId(),p.userId(),run,revision);
        if(changed!=1)throw new RunApplicationService.ConflictException("计划已被更新，请刷新后再修改");return plan(p,run);
    }
    public List<Map<String,Object>> permissions(Principal p,UUID run){
        owned(p,run);var result=new ArrayList<Map<String,Object>>();
        for(String scope:List.of("network","command","browser","sandbox"))for(var row:db.queryForList("SELECT granted_at FROM run_"+scope+"_permissions WHERE tenant_id=? AND user_id=? AND run_id=?",p.tenantId(),p.userId(),run))result.add(Map.of("scope",scope,"grantedAt",row.get("granted_at")));
        return result;
    }
    @Transactional public void revoke(Principal p,UUID run,String scope){
        var task=owned(p,run);if(!Set.of("network","command","browser","sandbox").contains(scope))throw new IllegalArgumentException("授权类型无效");
        db.update("DELETE FROM run_"+scope+"_permissions WHERE tenant_id=? AND user_id=? AND run_id=?",p.tenantId(),p.userId(),run);
        // A consumed operation approval must not silently recreate a revoked task grant.
        db.update("UPDATE run_approvals SET expires_at=now() WHERE tenant_id=? AND user_id=? AND run_id=? AND status='approved'",p.tenantId(),p.userId(),run);
        notify(p,"revoked:"+UUID.randomUUID(),"已撤销任务授权；后续调用重新申请，已开始的操作可用停止任务中断",task.id());
    }
    public Map<String,Object> usage(Principal p){
        var settings=db.queryForList("SELECT price_per_million,reminder_tokens FROM usage_preferences WHERE tenant_id=? AND user_id=?",p.tenantId(),p.userId());
        var preference=settings.isEmpty()?Map.<String,Object>of("price_per_million",0,"reminder_tokens",100000):settings.get(0);
        var list=db.queryForList("""
            SELECT r.id,r.session_id,s.title,r.status,r.token_used,r.reason_code,r.created_at,
            EXTRACT(EPOCH FROM(COALESCE(r.finished_at,now())-COALESCE(r.started_at,r.created_at)))::bigint AS seconds,
            (SELECT count(*) FROM operation_attempts a WHERE a.tenant_id=r.tenant_id AND a.run_id=r.id AND a.kind='model') AS model_calls,
            (SELECT count(*) FROM operation_attempts a WHERE a.tenant_id=r.tenant_id AND a.run_id=r.id AND a.kind='tool') AS tool_calls
            FROM runs r JOIN sessions s ON s.tenant_id=r.tenant_id AND s.id=r.session_id
            WHERE r.tenant_id=? AND r.user_id=? AND s.kind='chat' AND s.deleted_at IS NULL ORDER BY r.created_at DESC LIMIT 100
            """,p.tenantId(),p.userId());
        double price=((Number)preference.get("price_per_million")).doubleValue();long threshold=((Number)preference.get("reminder_tokens")).longValue();
        for(var row:list){long tokens=((Number)row.get("token_used")).longValue();row.put("estimatedCost",tokens*price/1_000_000d);row.put("reminderReached",tokens>=threshold);}
        return Map.of("tasks",list,"settings",preference,"currency","CNY","estimateNote","单价由用户填写，按累计Token粗估，不等于供应商账单；未区分输入、输出和缓存价格。");
    }
    public void usageSettings(Principal p,double price,long threshold){if(!Double.isFinite(price)||price<0||price>100000||threshold<1)throw new IllegalArgumentException("单价或提醒额度无效");db.update("INSERT INTO usage_preferences VALUES(?,?,?,?)ON CONFLICT(tenant_id,user_id)DO UPDATE SET price_per_million=excluded.price_per_million,reminder_tokens=excluded.reminder_tokens",p.tenantId(),p.userId(),price,threshold);}
    public void notify(Principal p,String event,String title,UUID run){db.update("INSERT INTO user_notifications(id,tenant_id,user_id,event_key,title,run_id)VALUES(?,?,?,?,?,?)ON CONFLICT(tenant_id,user_id,event_key)DO NOTHING",UUID.randomUUID(),p.tenantId(),p.userId(),event,title,run);}
    public List<Map<String,Object>> notifications(Principal p){return db.queryForList("SELECT id,title,run_id,read_at,created_at FROM user_notifications WHERE tenant_id=? AND user_id=? ORDER BY created_at DESC LIMIT 100",p.tenantId(),p.userId());}
    public void readNotification(Principal p,UUID id){db.update("UPDATE user_notifications SET read_at=now() WHERE tenant_id=? AND user_id=? AND id=?",p.tenantId(),p.userId(),id);}
    public Map<String,Object> sources(Principal p,UUID run){
        var row=owned(p,run);var memories=db.queryForList("""
            SELECT m.memory_key,m.content,m.state,m.valid_until,ms.memory_version,ms.session_id,ms.source_seq,ms.evidence,s.title AS session_title
            FROM memory_sources ms JOIN memories m ON m.tenant_id=ms.tenant_id AND m.id=ms.memory_id
            LEFT JOIN sessions s ON s.tenant_id=ms.tenant_id AND s.user_id=ms.user_id AND s.id=ms.session_id AND s.deleted_at IS NULL
            WHERE ms.tenant_id=? AND ms.user_id=? AND ms.session_id=? ORDER BY ms.created_at DESC LIMIT 100
            """,p.tenantId(),p.userId(),row.sessionId());
        var evidence=db.queryForList("SELECT state::text FROM checkpoints WHERE tenant_id=? AND user_id=? AND run_id=? ORDER BY version DESC LIMIT 1",p.tenantId(),p.userId(),run);
        var knowledge=db.queryForList("""
            SELECT m.seq,m.payload->>'content' AS content FROM messages m
            WHERE m.tenant_id=? AND m.user_id=? AND m.run_id=? AND m.role='tool'
            AND EXISTS(SELECT 1 FROM tool_calls t WHERE t.tenant_id=m.tenant_id AND t.run_id=m.run_id AND t.call_id=m.tool_call_id AND t.tool_name IN('knowledge_search','knowledge_read')) ORDER BY m.seq LIMIT 30
            """,p.tenantId(),p.userId(),run);
        return Map.of("memories",memories,"knowledge",knowledge,"checkpoint",evidence.isEmpty()?Map.of():json.map(evidence.get(0).get("state").toString()));
    }
    public Map<String,Object> propose(Principal p,UUID run,String filesJson)throws Exception{
        owned(p,run);var project=projects.required(p,run);var files=json.tree(filesJson);if(!files.isArray()||files.isEmpty()||files.size()>20)throw new IllegalArgumentException("批量修改需要1～20个文件");
        var paths=new HashSet<String>();var previews=new ArrayList<Map<String,Object>>();
        for(var f:files){String path=f.path("path").asText();if(!paths.add(path))throw new IllegalArgumentException("文件路径重复");previews.add(Map.of("path",path,"diff",projects.preview(project,path,f.path("content").asText(),f.path("expectedSha256").asText())));}
        var stored=new ArrayList<Map<String,Object>>();int i=0;for(var f:files){var entry=new LinkedHashMap<>(json.map(f.toString()));entry.put("previewDiff",previews.get(i++).get("diff"));stored.add(entry);}
        UUID id=UUID.randomUUID();db.update("INSERT INTO file_change_batches(id,tenant_id,user_id,run_id,files)VALUES(?,?,?,?,?::jsonb)",id,p.tenantId(),p.userId(),run,json.write(stored));return Map.of("batchId",id,"previews",previews,"status","draft");
    }
    public List<Map<String,Object>> batches(Principal p,UUID run){owned(p,run);return db.queryForList("SELECT id,status,files::text,result::text FROM file_change_batches WHERE tenant_id=? AND user_id=? AND run_id=? ORDER BY created_at",p.tenantId(),p.userId(),run);}
    public synchronized Map<String,Object> applyBatch(Principal p,UUID run,UUID id)throws Exception{
        owned(p,run);var rows=db.queryForList("SELECT files::text,status FROM file_change_batches WHERE tenant_id=? AND user_id=? AND run_id=? AND id=?",p.tenantId(),p.userId(),run,id);if(rows.isEmpty())throw new RunApplicationService.NotFoundException("修改批次不存在");
        if(!rows.get(0).get("status").equals("draft"))throw new RunApplicationService.ConflictException("批次已处理，请刷新");
        var files=json.tree(rows.get(0).get("files").toString());var project=projects.required(p,run);
        for(var f:files)projects.preview(project,f.path("path").asText(),f.path("content").asText(),f.path("expectedSha256").asText());
        if(db.update("UPDATE file_change_batches SET status='applying' WHERE id=? AND status='draft'",id)!=1)throw new RunApplicationService.ConflictException("批次正在处理");
        var result=new ArrayList<Map<String,Object>>();try{for(var f:files)result.add(projects.write(p,run,f.path("path").asText(),f.path("content").asText(),f.path("expectedSha256").asText()));
            db.update("UPDATE file_change_batches SET status='applied',result=?::jsonb WHERE id=?",json.write(result),id);return Map.of("status","applied","changes",result);
        }catch(Exception e){db.update("UPDATE file_change_batches SET status='needs_check',result=?::jsonb WHERE id=?",json.write(result),id);throw new IllegalStateException("批次未全部完成，已应用修改保留日志，请在项目面板检查和撤销",e);}
    }
}
