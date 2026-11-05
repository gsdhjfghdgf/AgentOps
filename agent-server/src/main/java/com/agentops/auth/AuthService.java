package com.agentops.auth;

import com.agentops.domain.Primitives.Principal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service
public class AuthService {
    private final AccountRepo repo;private final AuthRateLimiter limiter;private final Clock clock;
    private final Pbkdf2PasswordEncoder passwords=Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final String dummyHash=passwords.encode(UUID.randomUUID().toString());
    private final SecureRandom random=new SecureRandom();
    private final UUID legacyTenant,legacyUser;
    public AuthService(AccountRepo repo,AuthRateLimiter limiter,Clock clock,
                       @Value("${agent.accounts.legacy-tenant:11111111-1111-1111-1111-111111111111}")UUID legacyTenant,
                       @Value("${agent.accounts.legacy-user:22222222-2222-2222-2222-222222222222}")UUID legacyUser){this.repo=repo;this.limiter=limiter;this.clock=clock;this.legacyTenant=legacyTenant;this.legacyUser=legacyUser;}
    public record Profile(UUID id,UUID tenantId,UUID userId,String username,String displayName,Instant createdAt){}
    public record Login(Profile account,String accessToken,Instant expiresAt){@Override public String toString(){return "Login[redacted]";}}
    public record Authenticated(Profile account,UUID sessionId){public Principal principal(){return Principal.of(account.tenantId(),account.userId());}}
    public boolean setupRequired(){return !repo.anyAccount();}
    @Transactional
    public Profile register(String username,String name,String password,boolean inheritLegacy,boolean loopback,String address){
        limiter.consume("register:"+address,10);
        String login=username(username),display=displayName(name);validatePassword(password);
        // Serialize the first-account decision; two registrations cannot both inherit the old workspace.
        repo.lockRegistration();boolean first=!repo.anyAccount();
        if(inheritLegacy&&(!first||!loopback))throw new ResponseStatusException(HttpStatus.CONFLICT,"旧工作空间仅可由本机创建的第一个账号接管");
        if(repo.byUsername(login).isPresent())throw new ResponseStatusException(HttpStatus.CONFLICT,"用户名已被使用，请更换");
        return profile(repo.insert(inheritLegacy?legacyTenant:UUID.randomUUID(),inheritLegacy?legacyUser:UUID.randomUUID(),login,display,passwords.encode(password)));
    }
    @Transactional
    public Login login(String username,String password,boolean remember,String device,String address){
        String login=username(username);validatePasswordInput(password);
        limiter.consume("login-ip:"+address,60);limiter.consume("login-account:"+login,12);
        var account=repo.byUsername(login).orElse(null);
        boolean valid=passwords.matches(password,account==null?dummyHash:account.passwordHash());
        if(!valid||account==null)throw unauthorized("用户名或密码不正确");
        // Lock against a simultaneous password change before issuing a new session.
        var locked=repo.required(account.id(),true);
        if(!locked.passwordHash().equals(account.passwordHash()))throw unauthorized("密码已更新，请重新登录");
        if(repo.sessions(account.id(),clock.instant()).size()>=20)throw new ResponseStatusException(HttpStatus.CONFLICT,"有效登录已达20个，请先从已登录设备退出部分会话");
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now=clock.instant(),expires=now.plus(remember?Duration.ofDays(30):Duration.ofHours(12));
        String label=deviceLabel(device);
        repo.createSession(UUID.randomUUID(),account.id(),digest(token),label,now,expires);
        return new Login(profile(locked),token,expires);
    }
    public Authenticated authenticate(String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))throw unauthorized("请先登录");
        var session=repo.authenticate(digest(token),clock.instant()).orElseThrow(()->unauthorized("登录已失效，请重新登录"));
        if(session.lastUsedAt().isBefore(clock.instant().minusSeconds(60)))repo.touch(session.id(),clock.instant());
        return new Authenticated(profile(repo.required(session.accountId(),false)),session.id());
    }
    public boolean active(UUID id){return repo.active(id,clock.instant());}
    @Transactional public Profile rename(Authenticated auth,String name){repo.rename(auth.account().id(),displayName(name));return profile(repo.required(auth.account().id(),false));}
    @Transactional public void changePassword(Authenticated auth,String oldPassword,String newPassword){
        validatePasswordInput(oldPassword);validatePassword(newPassword);limiter.consume("password:"+auth.account().id(),8);
        var account=repo.required(auth.account().id(),true);
        if(!passwords.matches(oldPassword,account.passwordHash()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"当前密码不正确");
        if(passwords.matches(newPassword,account.passwordHash()))throw new IllegalArgumentException("新密码不能与当前密码相同");
        repo.password(account.id(),passwords.encode(newPassword));repo.revokeAll(account.id());
    }
    public List<Map<String,Object>> sessions(Authenticated auth){return repo.sessions(auth.account().id(),clock.instant()).stream().map(s->Map.<String,Object>of("id",s.id(),"device",s.device(),"createdAt",s.createdAt(),"lastUsedAt",s.lastUsedAt(),"expiresAt",s.expiresAt(),"current",s.id().equals(auth.sessionId()))).toList();}
    public void logout(Authenticated auth){repo.revoke(auth.account().id(),auth.sessionId());}
    public void revoke(Authenticated auth,UUID session){repo.revoke(auth.account().id(),session);}
    public void logoutAll(Authenticated auth){repo.revokeAll(auth.account().id());}
    static Profile profile(AccountRepo.Account account){return new Profile(account.id(),account.tenantId(),account.userId(),account.username(),account.displayName(),account.createdAt());}
    static String deviceLabel(String value){
        String ua=value==null?"":value.toLowerCase(Locale.ROOT);
        String platform=ua.contains("windows")?"Windows":ua.contains("android")?"Android":ua.contains("iphone")||ua.contains("ipad")?"iOS":ua.contains("macintosh")?"macOS":ua.contains("linux")?"Linux":"其他设备";
        String client=ua.contains("electron/")?"AgentOps 桌面端":ua.contains("edg/")?"Edge":ua.contains("chrome/")?"Chrome":ua.contains("firefox/")?"Firefox":ua.contains("safari/")?"Safari":ua.equals("node")?"API 客户端":"网页客户端";
        return platform+" · "+client;
    }
    static String username(String value){if(value==null||!value.matches("[a-zA-Z][a-zA-Z0-9_.-]{2,31}"))throw new IllegalArgumentException("用户名须为3～32位字母、数字或._-，以字母开头");return value.toLowerCase(Locale.ROOT);}
    static String displayName(String value){if(value==null||value.isBlank()||value.codePointCount(0,value.length())>24||value.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("显示名称须为1～24个字符，不含控制字符");return value.strip();}
    static void validatePasswordInput(String value){if(value==null||value.isEmpty()||value.length()>128)throw new IllegalArgumentException("密码长度须为1～128个字符");}
    static void validatePassword(String value){validatePasswordInput(value);if(value.length()<10||value.isBlank())throw new IllegalArgumentException("密码至少10位，建议使用不易猜测的长密码");}
    static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static ResponseStatusException unauthorized(String text){return new ResponseStatusException(HttpStatus.UNAUTHORIZED,text);}
}
