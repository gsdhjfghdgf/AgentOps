package com.agentops.security;

import com.agentops.auth.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.mock.web.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SecurityHardeningTest {
    @TempDir Path temp;
    @Test void privateAndReservedRangesAreNotPublicDestinations()throws Exception{
        for(String address:List.of("0.0.0.1","127.0.0.1","10.0.0.1","172.16.0.1","192.168.1.1","169.254.169.254","100.64.0.1","198.18.0.1","203.0.113.1","255.255.255.255","::1","fc00::1","fe80::1","64:ff9b::a00:1","2001:db8::1","2002:7f00:1::1"))
            assertThatThrownBy(()->PublicNetworkPolicy.requirePublic(InetAddress.getByName(address))).as(address).isInstanceOf(IllegalArgumentException.class);
        for(String address:List.of("8.8.8.8","1.1.1.1","2001:4860:4860::8888"))PublicNetworkPolicy.requirePublic(InetAddress.getByName(address));
    }
    @Test void untrustedUrlsCannotUseAlternateSchemesCredentialsOrEncodedHosts(){
        for(String address:List.of("file:///etc/passwd","gopher://127.0.0.1/","javascript:alert(1)","relative","http://user:secret@example.com/","http://%31%32%37.0.0.1/","http://example.com:22/","http://example.com/\r\nx:y"))assertThatThrownBy(()->PublicNetworkPolicy.uri(address)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void childProcessesDoNotInheritCredentialsOrInterpreterInjectionHooks(){
        var builder=new ProcessBuilder("unused");builder.environment().putAll(Map.of("MODEL_API_KEY","secret","SPRING_DATASOURCE_PASSWORD","secret","AWS_SECRET_ACCESS_KEY","secret","NODE_OPTIONS","--require malicious.js","PYTHONPATH","malicious","BASH_ENV","malicious","GIT_CONFIG_COUNT","1","PATH","safe-path","TEMP","safe-temp"));
        ProcessEnvironment.sanitize(builder);
        assertThat(builder.environment()).containsEntry("PATH","safe-path").containsEntry("TEMP","safe-temp").doesNotContainKeys("MODEL_API_KEY","SPRING_DATASOURCE_PASSWORD","AWS_SECRET_ACCESS_KEY","NODE_OPTIONS","PYTHONPATH","BASH_ENV","GIT_CONFIG_COUNT");
    }
    @Test void ratesAreBoundedPerScopeAndRecoverInTheNextWindow(){
        var time=mock(Clock.class);when(time.millis()).thenReturn(0L);var guard=new TrafficGuard(time);
        assertThat(guard.allow("user-a",2)).isTrue();assertThat(guard.allow("user-a",2)).isTrue();assertThat(guard.allow("user-a",2)).isFalse();assertThat(guard.allow("user-b",2)).isTrue();
        when(time.millis()).thenReturn(60001L);assertThat(guard.allow("user-a",2)).isTrue();
    }
    @Test void realTimeConnectionsHaveAccountAndGlobalLimitsAndLeasesAreReleased(){
        var guard=new TrafficGuard(Clock.systemUTC());UUID owner=UUID.randomUUID();var leases=new ArrayList<TrafficGuard.StreamLease>();
        for(int i=0;i<4;i++)leases.add(guard.acquireStream(owner));assertThat(guard.acquireStream(owner)).isNull();
        leases.get(0).close();leases.get(0).close();assertThat(guard.acquireStream(owner)).isNotNull();
        for(int i=0;i<60;i++)assertThat(guard.acquireStream(UUID.randomUUID())).isNotNull();assertThat(guard.acquireStream(UUID.randomUUID())).isNull();
    }
    @Test void pathNormalizationTricksAreRejectedBeforeTheyReachTheController()throws Exception{
        var filter=new SecurityHeadersFilter(new TrafficGuard(Clock.systemUTC()));
        for(String path:List.of("/api;ignored/sessions","/api//sessions","/api/%2fsessions","/api/%252fsessions","/api/%2e%2e/sessions")){
            var response=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest("GET",path),response,(r,s)->{throw new AssertionError("ambiguous route reached handler");});assertThat(response.getStatus()).as(path).isEqualTo(400);
        }
    }
    @Test void rawIdentityHeadersNeverAuthenticateAnEncodedApiPath()throws Exception{
        var service=mock(AuthService.class);when(service.authenticate(any())).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED));
        for(String path:List.of("/%61pi/sessions","/api;ignored/sessions","/actuator/metrics")){
            var response=new MockHttpServletResponse();new AuthFilter(service,new ObjectMapper()).doFilter(new MockHttpServletRequest("GET",path),response,(r,s)->{throw new AssertionError("identity bypass");});assertThat(response.getStatus()).isEqualTo(401);
        }
    }
    @Test void enrollmentProofIsPersistentAndWrongValuesAreRejected()throws Exception{
        Path file=temp.resolve("registration.key");var key=new RegistrationKey(file.toString());String proof=Files.readString(file);
        assertThat(proof).hasSize(43);assertThat(key.matches(proof)).isTrue();assertThat(new RegistrationKey(file.toString()).matches(proof)).isTrue();assertThat(key.matches("wrong")).isFalse();assertThat(key.matches(null)).isFalse();
    }
    @Test void opaqueOriginRegistrationWithoutTheLocalProofCannotClaimData()throws Exception{
        var service=mock(AuthService.class);var key=new RegistrationKey(temp.resolve("key").toString());
        var mvc=MockMvcBuilders.standaloneSetup(new AuthController(service,key)).setControllerAdvice(new com.agentops.api.web.ApiExceptionHandler()).build();
        mvc.perform(post("/api/auth/register").contentType("application/json").content("{\"username\":\"attacker\",\"displayName\":\"attacker\",\"password\":\"attacker-password\",\"inheritLegacy\":true,\"registrationKey\":\""+"x".repeat(43)+"\"}")).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
}
