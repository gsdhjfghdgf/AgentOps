package com.agentops.operations;

import com.agentops.api.RunApplicationService;
import com.agentops.api.web.ApiController;
import com.agentops.domain.Primitives.Principal;
import com.agentops.persistence.*;
import com.agentops.infra.Json;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/operations")
public class OperationsController {
    private final OperationsService operations;private final McpService mcp;private final ScheduleService schedules;private final ExportService exports;private final SandboxService sandbox;private final RunApplicationService app;private final ExecutionRepo execution;private final JdbcTemplate db;private final Json json;private final MemoryRepo memory;private final com.agentops.memory.MemoryService memoryService;private final com.agentops.browser.BrowserService browser;
    public OperationsController(OperationsService operations,McpService mcp,ScheduleService schedules,ExportService exports,SandboxService sandbox,RunApplicationService app,ExecutionRepo execution,JdbcTemplate db,Json json,MemoryRepo memory,com.agentops.memory.MemoryService memoryService,com.agentops.browser.BrowserService browser,TaskProgressService progress){this.operations=operations;this.mcp=mcp;this.schedules=schedules;this.exports=exports;this.sandbox=sandbox;this.app=app;this.execution=execution;this.db=db;this.json=json;this.memory=memory;this.memoryService=memoryService;this.browser=browser;this.progress=progress;}
    private final TaskProgressService progress;
    private Principal p(String tenant,String user){return ApiController.principal(tenant,user);}

    @GetMapping("/runs/{run}/progress") public Object progress(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID run){return progress.snapshot(p(t,u),run);}
    @GetMapping("/runs/{run}/plan") public Map<String,Object> plan(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID run){return operations.plan(p(t,u),run);}
    public record Plan(long revision,List<Map<String,Object>> steps){}
    @PutMapping("/runs/{run}/plan") public Map<String,Object> savePlan(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID run,@RequestBody Plan in){return operations.savePlan(p(t,u),run,in.revision(),json.write(in.steps()));}
    @GetMapping("/runs/{run}/steps") public Map<String,Object> steps(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID run){operations.owned(p(t,u),run);return Map.of("steps",execution.listSteps(UUID.fromString(t),run));}
    public record Retry(String stepId){}
    @PostMapping("/runs/{run}/retry") public Object retry(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID run,@RequestBody Retry in){var owner=p(t,u);var task=operations.owned(owner,run);if(!task.status().terminal())throw new RunApplicationService.ConflictException("请先停止当前任务再重试步骤");
        String title=null;
        for(var item:json.tree(json.write(operations.plan(owner,run).get("steps"))))if(item.path("id").asText().equals(in.stepId())&&!item.path("status").asText().equals("done"))title=item.path("title").asText();
        if(title==null)throw new IllegalArgumentException("请选择未完成的计划步骤");
        if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM tool_calls WHERE tenant_id=? AND user_id=? AND run_id=? AND status='unknown')",Boolean.class,owner.tenantId(),owner.userId(),run)))throw new RunApplicationService.ConflictException("原任务有结果未知的操作，请先核对，避免重复副作用");
        return app.createRun(owner,task.sessionId(),new RunApplicationService.CreateRunRequest(null,"仅重试原任务 "+run+" 中的步骤："+title+"。先检查已有产物和当前文件，复用已完成工作。不要重新执行其他步骤或重复外部写入；发现原操作结果未知时先询问用户。", "step_retry",null,null,null));
    }
    @GetMapping("/runs/{run}/permissions") public Object permissions(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID run){return Map.of("permissions",operations.permissions(p(t,u),run));}
    @DeleteMapping("/runs/{run}/permissions/{scope}") public Object revoke(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID run,@PathVariable String scope)throws Exception{operations.revoke(p(t,u),run,scope);if(Set.of("network","browser").contains(scope))browser.close(p(t,u),run);return Map.of("ok",true);}
    @GetMapping("/runs/{run}/sources") public Object sources(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID run){return operations.sources(p(t,u),run);}
    @GetMapping("/runs/{run}/batches") public Object batches(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID run){return Map.of("batches",operations.batches(p(t,u),run));}
    @PostMapping("/runs/{run}/batches/{id}/apply") public Object apply(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID run,@PathVariable UUID id)throws Exception{return operations.applyBatch(p(t,u),run,id);}
    @GetMapping("/runs/{run}/batches/{id}/preview") public Object previewBatch(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID run,@PathVariable UUID id)throws Exception{operations.owned(p(t,u),run);var row=operations.batches(p(t,u),run).stream().filter(r->r.get("id").equals(id)).findFirst().orElseThrow(()->new RunApplicationService.NotFoundException("批次不存在"));return Map.of("files",json.list(row.get("files").toString()));}
    public record Export(String format,String title,String content){}
    @PostMapping("/runs/{run}/export") public Object export(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID run,@RequestBody Export in)throws Exception{return exports.export(p(t,u),run,in.format(),in.title(),in.content());}
    @GetMapping("/artifacts/{id}/preview") public Object preview(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID id)throws Exception{return exports.preview(p(t,u),id);}
    @GetMapping("/mcp") public Object mcp(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u){return Map.of("connections",mcp.list(p(t,u)));}
    @PostMapping("/mcp") public Object saveMcp(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@RequestBody McpService.Input in){return Map.of("id",mcp.save(p(t,u),in));}
    public record Enabled(boolean enabled){}
    @PutMapping("/mcp/{id}") public Object mcpEnabled(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID id,@RequestBody Enabled in){mcp.enabled(p(t,u),id,in.enabled());return Map.of("ok",true);}
    @DeleteMapping("/mcp/{id}") public Object deleteMcp(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID id){mcp.delete(p(t,u),id);return Map.of("ok",true);}
    @PostMapping("/mcp/{id}/test") public Object testMcp(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID id)throws Exception{return Map.of("tools",mcp.tools(p(t,u),id));}
    @GetMapping("/schedules") public Object schedules(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u){return Map.of("schedules",schedules.list(p(t,u)));}
    @PostMapping("/schedules") public Object schedule(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@RequestBody ScheduleService.Input in){return Map.of("id",schedules.create(p(t,u),in));}
    @PutMapping("/schedules/{id}") public Object scheduleEnabled(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID id,@RequestBody Enabled in){schedules.enable(p(t,u),id,in.enabled());return Map.of("ok",true);}
    @DeleteMapping("/schedules/{id}") public Object deleteSchedule(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID id){schedules.delete(p(t,u),id);return Map.of("ok",true);}
    @GetMapping("/notifications") public Object notifications(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u){return Map.of("notifications",operations.notifications(p(t,u)));}
    @PostMapping("/notifications/{id}/read") public Object read(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID id){operations.readNotification(p(t,u),id);return Map.of("ok",true);}
    @GetMapping("/usage") public Object usage(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u){return operations.usage(p(t,u));}
    public record Usage(double price,long reminderTokens){}
    @PutMapping("/usage") public Object usageSettings(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@RequestBody Usage in){operations.usageSettings(p(t,u),in.price(),in.reminderTokens());return Map.of("ok",true);}
    @GetMapping("/sandbox") public Object sandbox(){return sandbox.status();}
    @GetMapping("/memories") public Object memories(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u){var owner=p(t,u);return Map.of("memories",db.queryForList("SELECT id,memory_key,content,authority,state,version,valid_until,(valid_until IS NOT NULL AND valid_until<=now())AS expired FROM memories WHERE tenant_id=? AND user_id=? ORDER BY updated_at DESC LIMIT 100",owner.tenantId(),owner.userId()));}
    @GetMapping("/memories/{id}/history") public Object history(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID id){var owner=p(t,u);return Map.of("history",db.queryForList("SELECT id,version,content,authority,state,valid_until,created_at FROM memory_revisions WHERE tenant_id=? AND user_id=? AND memory_id=? ORDER BY created_at DESC LIMIT 30",owner.tenantId(),owner.userId(),id),"sources",db.queryForList("SELECT ms.session_id,ms.source_seq,ms.evidence,ms.memory_version,s.title FROM memory_sources ms LEFT JOIN sessions s ON s.tenant_id=ms.tenant_id AND s.user_id=ms.user_id AND s.id=ms.session_id AND s.deleted_at IS NULL WHERE ms.tenant_id=? AND ms.user_id=? AND ms.memory_id=? ORDER BY ms.created_at DESC LIMIT 30",owner.tenantId(),owner.userId(),id));}
    public record Resolve(String content,boolean expire){}
    @PutMapping("/memories/{id}") public Object resolve(@RequestHeader("X-Tenant-Id")String t,@RequestHeader("X-User-Id")String u,@PathVariable UUID id,@RequestBody Resolve in){var owner=p(t,u);var rows=db.queryForList("SELECT scope_key,memory_key,kind FROM memories WHERE tenant_id=? AND user_id=? AND id=?",owner.tenantId(),owner.userId(),id);if(rows.isEmpty())throw new RunApplicationService.NotFoundException("记忆不存在");
        if(in.expire()){db.update("UPDATE memories SET valid_until=now(),version=version+1,embedding=NULL,embedding_model=NULL,embedding_version=NULL WHERE tenant_id=? AND user_id=? AND id=?",owner.tenantId(),owner.userId(),id);memory.memoryVersion(owner.tenantId(),owner.userId());}
        else{if(in.content()==null||in.content().isBlank()||in.content().length()>2000)throw new IllegalArgumentException("内容需要1～2000字");var row=rows.get(0);memoryService.saveExplicit(owner,row.get("scope_key").toString(),row.get("memory_key").toString(),row.get("kind").toString(),in.content(),null);}
        return Map.of("ok",true);
    }
}
