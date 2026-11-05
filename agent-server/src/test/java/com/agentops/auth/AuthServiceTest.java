package com.agentops.auth;

import org.junit.jupiter.api.*;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    final AccountRepo repo=mock(AccountRepo.class);final AuthRateLimiter limiter=mock(AuthRateLimiter.class);
    final Instant now=Instant.parse("2026-11-04T00:00:00Z");final UUID tenant=UUID.randomUUID(),user=UUID.randomUUID(),accountId=UUID.randomUUID();
    final String password="my-long-test-password";
    final Pbkdf2PasswordEncoder encoder=Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    AuthService service;AccountRepo.Account account;
    @BeforeEach void setup(){
        service=new AuthService(repo,limiter,Clock.fixed(now,ZoneOffset.UTC),tenant,user);
        account=new AccountRepo.Account(accountId,tenant,user,"tester","测试账号",encoder.encode(password),now);
    }
    @Test void firstLocalAccountCanExplicitlyInheritOnlyTheConfiguredLegacyOwner(){
        when(repo.insert(any(),any(),anyString(),anyString(),anyString())).thenAnswer(i->new AccountRepo.Account(accountId,i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),now));
        var profile=service.register("TestUser","测试用户",password,true,true,"127.0.0.1");
        assertThat(profile.tenantId()).isEqualTo(tenant);assertThat(profile.userId()).isEqualTo(user);assertThat(profile.username()).isEqualTo("testuser");
        var hash=org.mockito.ArgumentCaptor.forClass(String.class);verify(repo).insert(eq(tenant),eq(user),eq("testuser"),eq("测试用户"),hash.capture());
        assertThat(hash.getValue()).doesNotContain(password);assertThat(encoder.matches(password,hash.getValue())).isTrue();
    }
    @Test void remoteOrLaterRegistrationCannotClaimHistoricalData(){
        assertThatThrownBy(()->service.register("tester","测试",password,true,false,"10.0.0.1")).isInstanceOf(ResponseStatusException.class);
        when(repo.anyAccount()).thenReturn(true);
        assertThatThrownBy(()->service.register("tester","测试",password,true,true,"127.0.0.1")).isInstanceOf(ResponseStatusException.class);
        verify(repo,never()).insert(any(),any(),anyString(),anyString(),anyString());
    }
    @Test void newAccountsHaveDistinctServerAssignedOwners(){
        when(repo.insert(any(),any(),anyString(),anyString(),anyString())).thenAnswer(i->new AccountRepo.Account(accountId,i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),now));
        var profile=service.register("tester","测试",password,false,true,"127.0.0.1");
        assertThat(profile.tenantId()).isNotEqualTo(tenant);assertThat(profile.userId()).isNotEqualTo(user);
    }
    @Test void opaqueTokensAreRandomAndOnlyTheirDigestIsStored(){
        when(repo.byUsername("tester")).thenReturn(Optional.of(account));when(repo.required(accountId,true)).thenReturn(account);
        var login=service.login("Tester",password,true,"Windows\nDesktop","127.0.0.1");
        assertThat(login.accessToken()).matches("[A-Za-z0-9_-]{43}");assertThat(login.expiresAt()).isEqualTo(now.plus(Duration.ofDays(30)));
        verify(repo).createSession(any(),eq(accountId),eq(AuthService.digest(login.accessToken())),eq("Windows · 网页客户端"),eq(now),eq(login.expiresAt()));
        assertThat(service.login("tester",password,false,"desktop","127.0.0.1").accessToken()).isNotEqualTo(login.accessToken());
    }
    @Test void unknownAndIncorrectCredentialsHaveTheSameSafeError(){
        var missing=catchThrowableOfType(()->service.login("tester",password,false,"","ip"),ResponseStatusException.class);
        when(repo.byUsername("tester")).thenReturn(Optional.of(account));
        var incorrect=catchThrowableOfType(()->service.login("tester","incorrect-password",false,"","ip"),ResponseStatusException.class);
        assertThat(missing.getStatusCode().value()).isEqualTo(401);assertThat(incorrect.getReason()).isEqualTo(missing.getReason());
    }
    @Test void concurrentPasswordUpdateCannotIssueASessionForTheOldPassword(){
        when(repo.byUsername("tester")).thenReturn(Optional.of(account));
        when(repo.required(accountId,true)).thenReturn(new AccountRepo.Account(accountId,tenant,user,"tester","测试",encoder.encode("different-password"),now));
        assertThatThrownBy(()->service.login("tester",password,false,"","ip")).isInstanceOf(ResponseStatusException.class);
        verify(repo,never()).createSession(any(),any(),anyString(),anyString(),any(),any());
    }
    @Test void expiredAndMalformedTokensCannotAuthenticate(){
        assertThatThrownBy(()->service.authenticate("not-a-token")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.authenticate("a".repeat(43))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void passwordChangesRevokeEveryLoginAndWrongPasswordsDoNotMutate(){
        when(repo.required(accountId,true)).thenReturn(account);
        var auth=new AuthService.Authenticated(AuthService.profile(account),UUID.randomUUID());
        assertThatThrownBy(()->service.changePassword(auth,"incorrect-password","another-new-password")).isInstanceOf(ResponseStatusException.class);
        verify(repo,never()).password(any(),anyString());verify(repo,never()).revokeAll(any());
        service.changePassword(auth,password,"another-new-password");
        verify(repo).revokeAll(accountId);
        var hash=org.mockito.ArgumentCaptor.forClass(String.class);verify(repo).password(eq(accountId),hash.capture());assertThat(encoder.matches("another-new-password",hash.getValue())).isTrue();
    }
    @Test void usernameDisplayNameAndPasswordBoundariesFailBeforePersistence(){
        for(String invalid:List.of("ab","../root","用户名","a".repeat(33)))assertThatThrownBy(()->service.register(invalid,"名字",password,false,true,"ip")).isInstanceOf(IllegalArgumentException.class);
        for(String invalid:List.of("","name\n","名".repeat(25)))assertThatThrownBy(()->service.register("tester",invalid,password,false,true,"ip")).isInstanceOf(IllegalArgumentException.class);
        for(String invalid:List.of("short"," ".repeat(10),"a".repeat(129)))assertThatThrownBy(()->service.register("tester","名字",invalid,false,true,"ip")).isInstanceOf(IllegalArgumentException.class);
        verify(repo,never()).insert(any(),any(),anyString(),anyString(),anyString());
    }
}
