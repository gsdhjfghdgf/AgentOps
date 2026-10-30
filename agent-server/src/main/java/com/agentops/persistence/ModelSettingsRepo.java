package com.agentops.persistence;

import com.agentops.domain.Primitives.Principal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

@Repository
public class ModelSettingsRepo {
    private final JdbcTemplate jdbc;
    public ModelSettingsRepo(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record Profile(UUID id,String url,String model,String keyCipher) {
        @Override public String toString(){return "Profile[id="+id+", model="+model+"]";}
    }
    public Optional<Profile> active(Principal owner) {
        return jdbc.query("""
                SELECT p.id,p.api_url,p.model_name,p.key_cipher FROM chat_model_profiles p
                JOIN chat_model_settings s ON s.profile_id=p.id AND s.tenant_id=p.tenant_id AND s.user_id=p.user_id
                WHERE s.tenant_id=? AND s.user_id=?
                """,Jdbc.reader(rs->new Profile(Jdbc.uuid(rs,"id"),rs.getString("api_url"),
                rs.getString("model_name"),rs.getString("key_cipher"))),owner.tenantId(),owner.userId()).stream().findFirst();
    }
    public Optional<Profile> find(Principal owner,UUID id) {
        return jdbc.query("SELECT id,api_url,model_name,key_cipher FROM chat_model_profiles WHERE tenant_id=? AND user_id=? AND id=?",
                Jdbc.reader(rs->new Profile(Jdbc.uuid(rs,"id"),rs.getString("api_url"),rs.getString("model_name"),rs.getString("key_cipher"))),
                owner.tenantId(),owner.userId(),id).stream().findFirst();
    }
    public void save(Principal owner,Profile profile) {
        insert(owner,profile);
        selectVersion(owner,profile.id());
    }
    public void insert(Principal owner,Profile profile) {
        jdbc.update("INSERT INTO chat_model_profiles(id,tenant_id,user_id,api_url,model_name,key_cipher) VALUES(?,?,?,?,?,?)",
                profile.id(),owner.tenantId(),owner.userId(),profile.url(),profile.model(),profile.keyCipher());
    }
    public void selectVersion(Principal owner,UUID version) {
        jdbc.update("""
                INSERT INTO chat_model_settings(tenant_id,user_id,profile_id) VALUES(?,?,?)
                ON CONFLICT(tenant_id,user_id) DO UPDATE SET profile_id=excluded.profile_id
                """,owner.tenantId(),owner.userId(),version);
    }
    public record Entry(UUID id,String name,UUID profileId) {}
    public record SavedEntry(Entry entry,Profile profile) {}
    public List<SavedEntry> catalog(Principal owner) {
        return jdbc.query("""
                SELECT e.id AS entry_id,e.name,p.id,p.api_url,p.model_name,p.key_cipher
                FROM chat_model_entries e JOIN chat_model_profiles p
                  ON p.id=e.profile_id AND p.tenant_id=e.tenant_id AND p.user_id=e.user_id
                WHERE e.tenant_id=? AND e.user_id=? AND e.deleted_at IS NULL ORDER BY e.created_at,e.id
                """,Jdbc.reader(rs->new SavedEntry(
                new Entry(Jdbc.uuid(rs,"entry_id"),rs.getString("name"),Jdbc.uuid(rs,"id")),
                new Profile(Jdbc.uuid(rs,"id"),rs.getString("api_url"),rs.getString("model_name"),rs.getString("key_cipher")))),
                owner.tenantId(),owner.userId());
    }
    public List<Entry> list(Principal owner) {
        return jdbc.query("""
                SELECT id,name,profile_id FROM chat_model_entries
                WHERE tenant_id=? AND user_id=? AND deleted_at IS NULL ORDER BY created_at,id
                """,Jdbc.reader(rs->new Entry(Jdbc.uuid(rs,"id"),rs.getString("name"),Jdbc.uuid(rs,"profile_id"))),
                owner.tenantId(),owner.userId());
    }
    public Optional<Entry> lockEntry(Principal owner,UUID id) {
        return jdbc.query("""
                SELECT id,name,profile_id FROM chat_model_entries
                WHERE tenant_id=? AND user_id=? AND id=? AND deleted_at IS NULL FOR UPDATE
                """,Jdbc.reader(rs->new Entry(Jdbc.uuid(rs,"id"),rs.getString("name"),Jdbc.uuid(rs,"profile_id"))),
                owner.tenantId(),owner.userId(),id).stream().findFirst();
    }
    public Optional<Entry> entry(Principal owner,UUID id) {
        return jdbc.query("""
                SELECT id,name,profile_id FROM chat_model_entries
                WHERE tenant_id=? AND user_id=? AND id=? AND deleted_at IS NULL
                """,Jdbc.reader(rs->new Entry(Jdbc.uuid(rs,"id"),rs.getString("name"),Jdbc.uuid(rs,"profile_id"))),
                owner.tenantId(),owner.userId(),id).stream().findFirst();
    }
    public void createEntry(Principal owner,Entry entry) {
        jdbc.update("INSERT INTO chat_model_entries(id,tenant_id,user_id,name,profile_id)VALUES(?,?,?,?,?)",
                entry.id(),owner.tenantId(),owner.userId(),entry.name(),entry.profileId());
        attach(owner,entry.profileId(),entry.id());
    }
    public void updateEntry(Principal owner,Entry before,String name,UUID version) {
        jdbc.update("""
                UPDATE chat_model_entries SET name=?,profile_id=?,updated_at=now()
                WHERE tenant_id=? AND user_id=? AND id=? AND deleted_at IS NULL
                """,name,version,owner.tenantId(),owner.userId(),before.id());
        attach(owner,version,before.id());
        // Editing an inactive entry must not change the user's selection.
        jdbc.update("UPDATE chat_model_settings SET profile_id=? WHERE tenant_id=? AND user_id=? AND profile_id=?",
                version,owner.tenantId(),owner.userId(),before.profileId());
    }
    private void attach(Principal owner,UUID profile,UUID entry) {
        jdbc.update("UPDATE chat_model_profiles SET entry_id=? WHERE tenant_id=? AND user_id=? AND id=?",
                entry,owner.tenantId(),owner.userId(),profile);
    }
    public void removeEntry(Principal owner,Entry entry) {
        jdbc.update("UPDATE chat_model_entries SET deleted_at=now() WHERE tenant_id=? AND user_id=? AND id=?",
                owner.tenantId(),owner.userId(),entry.id());
        jdbc.update("DELETE FROM chat_model_settings WHERE tenant_id=? AND user_id=? AND profile_id=?",
                owner.tenantId(),owner.userId(),entry.profileId());
    }
    public void reset(Principal owner){jdbc.update("DELETE FROM chat_model_settings WHERE tenant_id=? AND user_id=?",owner.tenantId(),owner.userId());}
}
