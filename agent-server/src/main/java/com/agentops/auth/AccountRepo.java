package com.agentops.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Login credentials stay separate from Agent task identities and artifact storage. */
@Repository
public class AccountRepo {
    private final JdbcTemplate db;
    public AccountRepo(JdbcTemplate db) { this.db=db; }
    public record Account(UUID id,UUID tenantId,UUID userId,String username,String displayName,String passwordHash,Instant createdAt) {}
    public record LoginSession(UUID id,UUID accountId,String device,Instant createdAt,Instant lastUsedAt,Instant expiresAt) {}
    private org.springframework.jdbc.core.RowMapper<Account> mapper(){return (r,n)->new Account(r.getObject("id",UUID.class),r.getObject("tenant_id",UUID.class),r.getObject("user_id",UUID.class),r.getString("username"),r.getString("display_name"),r.getString("password_hash"),r.getTimestamp("created_at").toInstant());}
    public boolean anyAccount(){return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM user_accounts)",Boolean.class));}
    public void lockRegistration(){db.execute("LOCK TABLE user_accounts IN EXCLUSIVE MODE");}
    public Optional<Account> byUsername(String username){return db.query("SELECT * FROM user_accounts WHERE username=?",mapper(),username).stream().findFirst();}
    public Account required(UUID id,boolean lock){return db.query("SELECT * FROM user_accounts WHERE id=?"+(lock?" FOR UPDATE":""),mapper(),id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("账号不存在"));}
    public Account insert(UUID tenant,UUID user,String username,String name,String hash){UUID id=UUID.randomUUID();db.update("INSERT INTO user_accounts(id,tenant_id,user_id,username,display_name,password_hash)VALUES(?,?,?,?,?,?)",id,tenant,user,username,name,hash);return required(id,false);}
    public void rename(UUID id,String name){db.update("UPDATE user_accounts SET display_name=?,updated_at=now() WHERE id=?",name,id);}
    public void password(UUID id,String hash){db.update("UPDATE user_accounts SET password_hash=?,password_changed_at=now(),updated_at=now() WHERE id=?",hash,id);}
    public void createSession(UUID id,UUID account,String hash,String device,Instant now,Instant expires){
        db.update("INSERT INTO login_sessions(id,account_id,token_hash,device,created_at,last_used_at,expires_at)VALUES(?,?,?,?,?,?,?)",id,account,hash,device,Timestamp.from(now),Timestamp.from(now),Timestamp.from(expires));
        // Bound retained login history without deleting a user's valid devices.
        db.update("DELETE FROM login_sessions WHERE account_id=? AND (revoked_at IS NOT NULL OR expires_at<now()) AND created_at<now()-interval '30 days'",account);
    }
    public Optional<LoginSession> authenticate(String hash,Instant now){return db.query("SELECT * FROM login_sessions WHERE token_hash=? AND revoked_at IS NULL AND expires_at>?",sessionMapper(),hash,Timestamp.from(now)).stream().findFirst();}
    public boolean active(UUID id,Instant now){return Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM login_sessions WHERE id=? AND revoked_at IS NULL AND expires_at>?)",Boolean.class,id,Timestamp.from(now)));}
    public void touch(UUID id,Instant now){db.update("UPDATE login_sessions SET last_used_at=? WHERE id=? AND last_used_at<?",Timestamp.from(now),id,Timestamp.from(now.minusSeconds(60)));}
    public List<LoginSession> sessions(UUID account,Instant now){return db.query("SELECT * FROM login_sessions WHERE account_id=? AND revoked_at IS NULL AND expires_at>? ORDER BY created_at DESC LIMIT 100",sessionMapper(),account,Timestamp.from(now));}
    public void revoke(UUID account,UUID session){db.update("UPDATE login_sessions SET revoked_at=now() WHERE account_id=? AND id=? AND revoked_at IS NULL",account,session);}
    public void revokeAll(UUID account){db.update("UPDATE login_sessions SET revoked_at=now() WHERE account_id=? AND revoked_at IS NULL",account);}
    private org.springframework.jdbc.core.RowMapper<LoginSession> sessionMapper(){return (r,n)->new LoginSession(r.getObject("id",UUID.class),r.getObject("account_id",UUID.class),r.getString("device"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("last_used_at").toInstant(),r.getTimestamp("expires_at").toInstant());}
}
