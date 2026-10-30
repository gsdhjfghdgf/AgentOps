package com.agentops.persistence;

import com.agentops.domain.Primitives.Principal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class CapabilityRepo {
    public record Preference(boolean enabled,boolean askApproval) {}
    public record UserSkill(UUID id,String name,String description,String keywords,String content,String sourceUrl) {}
    private final JdbcTemplate jdbc;
    public CapabilityRepo(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public List<String> activeSkills(Principal owner,UUID runId) {
        return jdbc.queryForList("SELECT skill_id FROM run_skill_activations WHERE tenant_id=? AND user_id=? AND run_id=? ORDER BY activated_at DESC LIMIT 3",
                String.class,owner.tenantId(),owner.userId(),runId);
    }
    public void activateSkill(Principal owner,UUID runId,String skillId) {
        jdbc.update("""
                INSERT INTO run_skill_activations(tenant_id,user_id,run_id,skill_id) VALUES(?,?,?,?)
                ON CONFLICT(tenant_id,user_id,run_id,skill_id) DO UPDATE SET activated_at=now()
                """,owner.tenantId(),owner.userId(),runId,skillId);
    }
    public Map<String,Preference> preferences(Principal owner,String kind) {
        var result=new HashMap<String,Preference>();
        jdbc.query("SELECT name,enabled,ask_approval FROM capability_preferences WHERE tenant_id=? AND user_id=? AND kind=?",
                rs->{result.put(rs.getString("name"),new Preference(rs.getBoolean("enabled"),rs.getBoolean("ask_approval")));},
                owner.tenantId(),owner.userId(),kind);
        return result;
    }
    public void preference(Principal owner,String kind,String name,boolean enabled,boolean ask) {
        jdbc.update("""
                INSERT INTO capability_preferences(tenant_id,user_id,kind,name,enabled,ask_approval) VALUES(?,?,?,?,?,?)
                ON CONFLICT(tenant_id,user_id,kind,name) DO UPDATE SET enabled=excluded.enabled,ask_approval=excluded.ask_approval
                """,owner.tenantId(),owner.userId(),kind,name,enabled,ask);
    }
    public List<UserSkill> skills(Principal owner) {
        return jdbc.query("SELECT id,name,description,keywords,content,source_url FROM user_skills WHERE tenant_id=? AND user_id=? ORDER BY created_at DESC",
                Jdbc.reader(rs->new UserSkill(Jdbc.uuid(rs,"id"),rs.getString("name"),rs.getString("description"),rs.getString("keywords"),
                        rs.getString("content"),rs.getString("source_url"))),owner.tenantId(),owner.userId());
    }
    @org.springframework.transaction.annotation.Transactional
    public UUID saveSkill(Principal owner,String name,String description,String keywords,String content,String source) {
        var previous=jdbc.query("SELECT id,name,description,keywords,content,source_url FROM user_skills WHERE tenant_id=? AND user_id=? AND name=? FOR UPDATE",
                Jdbc.reader(rs->new UserSkill(Jdbc.uuid(rs,"id"),rs.getString("name"),rs.getString("description"),rs.getString("keywords"),rs.getString("content"),rs.getString("source_url"))),owner.tenantId(),owner.userId(),name);
        if(!previous.isEmpty()) {var old=previous.get(0);if(!old.content().equals(content)||!old.description().equals(description)||!old.keywords().equals(keywords)||!old.sourceUrl().equals(source))jdbc.update("""
                INSERT INTO skill_versions(id,tenant_id,user_id,skill_id,name,description,keywords,content,source_url)
                VALUES(?,?,?,?,?,?,?,?,?)
                """,UUID.randomUUID(),owner.tenantId(),owner.userId(),old.id(),old.name(),old.description(),old.keywords(),old.content(),old.sourceUrl());}
        var id=UUID.randomUUID();
        return jdbc.queryForObject("""
                INSERT INTO user_skills(id,tenant_id,user_id,name,description,keywords,content,source_url) VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(tenant_id,user_id,name) DO UPDATE SET description=excluded.description,keywords=excluded.keywords,
                content=excluded.content,source_url=excluded.source_url RETURNING id
                """,UUID.class,id,owner.tenantId(),owner.userId(),name,description,keywords,content,source);
    }
    public List<Map<String,Object>> versions(Principal owner,UUID skillId){return jdbc.queryForList("SELECT id,name,source_url,created_at::text FROM skill_versions WHERE tenant_id=? AND user_id=? AND skill_id=? ORDER BY created_at DESC LIMIT 20",owner.tenantId(),owner.userId(),skillId);}
    public UserSkill version(Principal owner,UUID skillId,UUID versionId){var rows=jdbc.query("SELECT skill_id,name,description,keywords,content,source_url FROM skill_versions WHERE tenant_id=? AND user_id=? AND skill_id=? AND id=?",Jdbc.reader(rs->new UserSkill(Jdbc.uuid(rs,"skill_id"),rs.getString("name"),rs.getString("description"),rs.getString("keywords"),rs.getString("content"),rs.getString("source_url"))),owner.tenantId(),owner.userId(),skillId,versionId);return rows.stream().findFirst().orElseThrow(()->new com.agentops.api.RunApplicationService.NotFoundException("技能版本不存在"));}
    public boolean deleteSkill(Principal owner,UUID id) {
        return jdbc.update("DELETE FROM user_skills WHERE tenant_id=? AND user_id=? AND id=?",owner.tenantId(),owner.userId(),id)>0;
    }
}
