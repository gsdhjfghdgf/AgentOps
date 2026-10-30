package com.agentops.project;

import com.agentops.domain.Primitives.Principal;
import com.agentops.persistence.Jdbc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class ProjectRepo {
    public record Project(UUID id,String name,String root,String previewOrigin) {}
    public record Change(UUID id,UUID projectId,UUID runId,String path,String before,String beforeSha,String afterSha,String status,String createdAt) {}
    private final JdbcTemplate jdbc;
    public ProjectRepo(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<Project> list(Principal p){return jdbc.query("SELECT id,name,root_path,preview_origin FROM agent_projects WHERE tenant_id=? AND user_id=? ORDER BY created_at DESC",
            Jdbc.reader(r->new Project(Jdbc.uuid(r,"id"),r.getString("name"),r.getString("root_path"),r.getString("preview_origin"))),p.tenantId(),p.userId());}
    public Project get(Principal p,UUID id){return list(p).stream().filter(r->r.id().equals(id)).findFirst().orElseThrow(()->new com.agentops.api.RunApplicationService.NotFoundException("项目不存在"));}
    public UUID attach(Principal p,String name,String root){return jdbc.queryForObject("""
            INSERT INTO agent_projects(id,tenant_id,user_id,name,root_path) VALUES(?,?,?,?,?)
            ON CONFLICT(tenant_id,user_id,root_path) DO UPDATE SET name=excluded.name RETURNING id
            """,UUID.class,UUID.randomUUID(),p.tenantId(),p.userId(),name,root);}
    public Optional<Project> selected(Principal p){var ids=jdbc.queryForList("SELECT project_id FROM project_selections WHERE tenant_id=? AND user_id=?",UUID.class,p.tenantId(),p.userId());return ids.isEmpty()||ids.get(0)==null?Optional.empty():Optional.of(get(p,ids.get(0)));}
    public void select(Principal p,UUID id){if(id!=null)get(p,id);jdbc.update("INSERT INTO project_selections(tenant_id,user_id,project_id) VALUES(?,?,?) ON CONFLICT(tenant_id,user_id) DO UPDATE SET project_id=excluded.project_id",p.tenantId(),p.userId(),id);}
    public void previewOrigin(Principal p,UUID id,String origin){get(p,id);jdbc.update("UPDATE agent_projects SET preview_origin=? WHERE tenant_id=? AND user_id=? AND id=?",origin,p.tenantId(),p.userId(),id);}
    public void record(Principal p,UUID id,UUID project,UUID run,String path,String before,String beforeSha,String afterSha){jdbc.update("""
            INSERT INTO project_file_changes(id,tenant_id,user_id,project_id,run_id,path,before_content,before_sha,after_sha)
            VALUES(?,?,?,?,?,?,?,?,?)
            """,id,p.tenantId(),p.userId(),project,run,path,before,beforeSha,afterSha);}
    public void status(Principal p,UUID id,String status){jdbc.update("UPDATE project_file_changes SET status=?,reverted_at=CASE WHEN ?='reverted' THEN now() ELSE reverted_at END WHERE tenant_id=? AND user_id=? AND id=?",status,status,p.tenantId(),p.userId(),id);}
    public List<Change> changes(Principal p,UUID project){get(p,project);return jdbc.query("""
            SELECT id,project_id,run_id,path,before_content,before_sha,after_sha,status,created_at::text FROM project_file_changes
            WHERE tenant_id=? AND user_id=? AND project_id=? ORDER BY created_at DESC LIMIT 100
            """,Jdbc.reader(r->new Change(Jdbc.uuid(r,"id"),Jdbc.uuid(r,"project_id"),Jdbc.uuid(r,"run_id"),r.getString("path"),r.getString("before_content"),r.getString("before_sha"),r.getString("after_sha"),r.getString("status"),r.getString("created_at"))),p.tenantId(),p.userId(),project);}
}
