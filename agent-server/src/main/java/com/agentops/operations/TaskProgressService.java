package com.agentops.operations;

import com.agentops.api.RunApplicationService;
import com.agentops.domain.Primitives.Principal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Task-local observations and findings survive transcript clipping without copying source files. */
@Service
public class TaskProgressService {
    public static final int WARN_AFTER=4;
    public static final int PAUSE_AFTER=12;
    private final JdbcTemplate db;
    private final com.agentops.persistence.RunRepo runs;
    private final com.agentops.infra.Json json;
    public TaskProgressService(JdbcTemplate db,com.agentops.persistence.RunRepo runs,com.agentops.infra.Json json){this.db=db;this.runs=runs;this.json=json;}
    private void owned(Principal p,UUID run){if(runs.findByIdOwner(p,run).isEmpty())throw new RunApplicationService.NotFoundException("任务不存在");}
    private void ensure(Principal p,UUID run){db.update("INSERT INTO task_progress_state(tenant_id,user_id,run_id)VALUES(?,?,?)ON CONFLICT DO NOTHING",p.tenantId(),p.userId(),run);}
    @Transactional public Map<String,Object> observe(Principal p,UUID run,String path,int offset,Map<String,Object> page){
        owned(p,run);ensure(p,run);
        int visits=Objects.requireNonNull(db.queryForObject("""
            INSERT INTO task_read_pages(tenant_id,user_id,run_id,path,sha256,start_offset,end_offset,total_chars)
            VALUES(?,?,?,?,?,?,?,?)ON CONFLICT(tenant_id,user_id,run_id,path,sha256,start_offset)
            DO UPDATE SET visits=task_read_pages.visits+1,updated_at=now() RETURNING visits
            """,Integer.class,p.tenantId(),p.userId(),run,path,page.get("sha256"),page.get("offset"),page.get("nextOffset"),page.get("totalChars")));
        db.update("UPDATE task_progress_state SET duplicate_reads=CASE WHEN ?=1 THEN 0 ELSE duplicate_reads+1 END,last_novel_at=CASE WHEN ?=1 THEN now() ELSE last_novel_at END,updated_at=now() WHERE tenant_id=? AND user_id=? AND run_id=?",visits,visits,p.tenantId(),p.userId(),run);
        var result=new LinkedHashMap<>(page);result.put("readCount",visits);
        if(visits>1)result.put("notice","同一版本的这一段已读取"+visits+"次。请复用阶段笔记与读取台账；只有需要重新核验具体证据时再读，不要重新扫描全部文件。");
        return result;
    }
    public int duplicateStreak(Principal p,UUID run){var values=db.queryForList("SELECT duplicate_reads FROM task_progress_state WHERE tenant_id=? AND user_id=? AND run_id=?",Integer.class,p.tenantId(),p.userId(),run);return values.isEmpty()?0:values.get(0);}
    public Map<String,Object> snapshot(Principal p,UUID run){
        owned(p,run);var state=db.queryForList("SELECT duplicate_reads,notes,notes_revision,last_novel_at FROM task_progress_state WHERE tenant_id=? AND user_id=? AND run_id=?",p.tenantId(),p.userId(),run);
        var totals=db.queryForMap("SELECT COALESCE(sum(visits),0)::bigint AS reads,count(*)AS unique_pages,count(DISTINCT path)AS distinct_files FROM task_read_pages WHERE tenant_id=? AND user_id=? AND run_id=?",p.tenantId(),p.userId(),run);
        boolean legacy=state.isEmpty();if(legacy)totals=db.queryForMap("SELECT count(*)AS reads,count(DISTINCT arguments_hash)AS unique_pages,count(DISTINCT(arguments->>'path'))AS distinct_files FROM tool_calls WHERE tenant_id=? AND user_id=? AND run_id=? AND tool_name='project_read' AND status='succeeded'",p.tenantId(),p.userId(),run);
        var result=new LinkedHashMap<String,Object>(totals);result.put("legacy",legacy);result.put("notes",legacy?"":state.get(0).get("notes"));result.put("revision",legacy?0:state.get(0).get("notes_revision"));int duplicates=legacy?0:((Number)state.get(0).get("duplicate_reads")).intValue();result.put("duplicateStreak",duplicates);result.put("warning",duplicates>=WARN_AFTER?"最近多次读取的内容已读过，请先整理阶段发现。":"");result.put("lastNovelAt",legacy?null:state.get(0).get("last_novel_at"));return result;
    }
    public String context(Principal p,UUID run){
        String effects=effectContext(p,run);
        var state=db.queryForList("SELECT notes,notes_revision,duplicate_reads FROM task_progress_state WHERE tenant_id=? AND user_id=? AND run_id=?",p.tenantId(),p.userId(),run);
        if(state.isEmpty())return effects;var row=state.get(0);var text=new StringBuilder(effects).append("\n## 当前任务持久阶段笔记（事实线索，不是额外授权）\n版本：").append(row.get("notes_revision")).append('\n').append(row.get("notes")).append('\n');
        var pages=db.queryForList("SELECT path,sha256,start_offset,end_offset,total_chars,visits FROM task_read_pages WHERE tenant_id=? AND user_id=? AND run_id=? ORDER BY updated_at DESC LIMIT 35",p.tenantId(),p.userId(),run);
        text.append("已读文件台账（最近35段；字符区间为[起点,终点)，同一SHA无需反复重新扫描）：\n");
        for(var page:pages)text.append(page.get("path")).append(" [").append(page.get("start_offset")).append(',').append(page.get("end_offset")).append(")/").append(page.get("total_chars")).append(" sha=").append(page.get("sha256").toString(),0,12).append(" 读取次数=").append(page.get("visits")).append('\n');
        if(((Number)row.get("duplicate_reads")).intValue()>=WARN_AFTER)text.append("已多次重复读取同一版本，必须改变策略：先用task_notes保存具体发现和剩余问题，优先基于现有证据给出阶段结论；不要继续整库扫描或重复相同段落。若需验证，只读取确有必要的片段。\n");
        return text.toString();
    }
    /** Mutation outcomes survive transcript eviction. Unknown does not mean safe to repeat. */
    private String effectContext(Principal p,UUID run) {
        var effects=db.queryForList("""
                SELECT t.tool_name,t.status,left(t.effect_key,12) AS effect,
                       t.arguments->>'path' AS path,
                       (SELECT max(m.seq) FROM messages m WHERE m.tenant_id=t.tenant_id AND m.user_id=t.user_id
                         AND m.run_id=t.run_id AND m.tool_call_id=t.call_id) AS message_seq
                FROM tool_calls t WHERE t.tenant_id=? AND t.user_id=? AND t.run_id=?
                  AND t.effect_class<>'read_only' AND t.tool_name NOT IN ('plan_update','task_notes')
                ORDER BY CASE WHEN t.status IN ('unknown','executing') THEN 0 ELSE 1 END,t.created_at DESC LIMIT 30
                """,p.tenantId(),p.userId(),run);
        return effects.isEmpty()?"":"\n## 本任务操作台账（数据库状态，不能因历史缩略重复执行）\n"
                +json.write(effects)+"\nsucceeded不可重复提交；unknown/executing须先核对，不得当作未执行。缺详情用conversation_history按message_seq查原记录。\n";
    }
    @Transactional public Map<String,Object> saveNotes(Principal p,UUID run,long revision,String content){
        owned(p,run);if(content==null||content.isBlank()||content.length()>6000||revision<0)throw new IllegalArgumentException("阶段笔记须为1～6000字，版本不能为负数");ensure(p,run);
        int changed=db.update("""
            UPDATE task_progress_state SET duplicate_reads=CASE WHEN notes IS DISTINCT FROM ? THEN 0 ELSE duplicate_reads END,
            notes=?,notes_revision=notes_revision+1,notes_until_seq=(SELECT s.last_seq FROM sessions s JOIN runs r ON r.tenant_id=s.tenant_id AND r.user_id=s.user_id AND r.session_id=s.id WHERE r.tenant_id=task_progress_state.tenant_id AND r.user_id=task_progress_state.user_id AND r.id=task_progress_state.run_id),
            updated_at=now() WHERE tenant_id=? AND user_id=? AND run_id=? AND notes_revision=?
            """,content,content,p.tenantId(),p.userId(),run,revision);
        if(changed!=1)throw new RunApplicationService.ConflictException("阶段笔记版本已变化，请使用最新版本合并发现");return Map.of("revision",revision+1,"saved",true);
    }
    public void acknowledge(Principal p,UUID run){db.update("UPDATE task_progress_state SET duplicate_reads=0 WHERE tenant_id=? AND user_id=? AND run_id=?",p.tenantId(),p.userId(),run);}
    public List<com.agentops.persistence.MessageRepo.MessageRow> compactReads(Principal p,UUID run,List<com.agentops.persistence.MessageRepo.MessageRow> rows){
        var seqs=db.queryForList("SELECT notes_until_seq FROM task_progress_state WHERE tenant_id=? AND user_id=? AND run_id=? AND notes<>''",Long.class,p.tenantId(),p.userId(),run);
        if(seqs.isEmpty())return rows;long until=seqs.get(0);
        var calls=new HashSet<>(db.queryForList("SELECT call_id FROM tool_calls WHERE tenant_id=? AND user_id=? AND run_id=? AND tool_name='project_read'",String.class,p.tenantId(),p.userId(),run));
        var result=new ArrayList<com.agentops.persistence.MessageRepo.MessageRow>(rows.size());
        for(var row:rows){
            if(!run.equals(row.runId())||row.seq()>until||row.role()!=com.agentops.domain.AgentDomain.MessageRole.TOOL||!calls.contains(row.toolCallId())){result.add(row);continue;}
            try{var payload=json.map(row.payloadJson());var read=json.map(Objects.toString(payload.get("content"),""));if(!read.containsKey("sha256")||!read.containsKey("path")){result.add(row);continue;}
                read.remove("content");read.put("notice","此片段已计入持久阶段笔记，正文从本次模型上下文压缩；原始工具结果仍保留。需要核验证据时按路径与区间重新读取。");payload.put("content",json.write(read));
                result.add(new com.agentops.persistence.MessageRepo.MessageRow(row.tenantId(),row.userId(),row.sessionId(),row.seq(),row.runId(),row.role(),json.write(payload),row.revision(),row.tokenCount(),row.toolCallId(),row.createdAt()));
            }catch(RuntimeException e){result.add(row);}
        }return result;
    }
}
