package com.agentops.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Clock;
import java.sql.Timestamp;

@Service
public class AuthRateLimiter {
    private final JdbcTemplate db;private final Clock clock;
    public AuthRateLimiter(JdbcTemplate db,Clock clock){this.db=db;this.clock=clock;}
    /** Separate transaction: a failed password must not roll back its attempt counter. */
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void consume(String scope,int maximum){
        var now=clock.instant();
        Integer count=db.queryForObject("""
                INSERT INTO auth_rate_limits(scope_hash,window_start,attempts)VALUES(?,?,1)
                ON CONFLICT(scope_hash)DO UPDATE SET
                  attempts=CASE WHEN auth_rate_limits.window_start<? THEN 1 ELSE auth_rate_limits.attempts+1 END,
                  window_start=CASE WHEN auth_rate_limits.window_start<? THEN excluded.window_start ELSE auth_rate_limits.window_start END
                RETURNING attempts
                """,Integer.class,AuthService.digest(scope),Timestamp.from(now),Timestamp.from(now.minusSeconds(600)),Timestamp.from(now.minusSeconds(600)));
        db.update("DELETE FROM auth_rate_limits WHERE window_start<?",Timestamp.from(now.minusSeconds(3600)));
        if(count==null||count>maximum)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"尝试次数过多，请在十分钟后再试");
    }
}
