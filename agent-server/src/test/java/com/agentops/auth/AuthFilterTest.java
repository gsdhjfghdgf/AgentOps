package com.agentops.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AuthFilterTest {
    final AuthService service=mock(AuthService.class);final AuthFilter filter=new AuthFilter(service,new ObjectMapper());
    @Test void headersAndSseQueryParametersCannotBypassLogin()throws Exception{
        when(service.authenticate(any())).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"请先登录"));
        for(String path:java.util.List.of("/api/sessions","/api/artifacts/id/download","/api/runs/id/events")){
            var request=new MockHttpServletRequest("GET",path);request.addHeader("X-User-Id",UUID.randomUUID().toString());request.setParameter("tenantId",UUID.randomUUID().toString());
            var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->{throw new AssertionError("unauthenticated handler invoked");});
            assertThat(response.getStatus()).isEqualTo(401);assertThat(response.getContentAsString()).contains("UNAUTHORIZED");
        }
    }
    @Test void authenticatedOwnerReplacesSpoofedHeaders()throws Exception{
        UUID tenant=UUID.randomUUID(),user=UUID.randomUUID();
        var account=new AuthService.Profile(UUID.randomUUID(),tenant,user,"tester","测试",Instant.EPOCH);
        when(service.authenticate("token")).thenReturn(new AuthService.Authenticated(account,UUID.randomUUID()));
        var request=new MockHttpServletRequest("GET","/api/sessions");request.addHeader("Authorization","Bearer token");request.addHeader("X-Tenant-Id","spoofed");request.addHeader("X-User-Id","spoofed");
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->{
            var wrapped=(jakarta.servlet.http.HttpServletRequest)req;
            assertThat(wrapped.getHeader("X-Tenant-Id")).isEqualTo(tenant.toString());assertThat(wrapped.getHeader("X-User-Id")).isEqualTo(user.toString());
            assertThat(java.util.Collections.list(wrapped.getHeaders("x-user-id"))).containsExactly(user.toString());assertThat(req.getAttribute(AuthFilter.ACCOUNT)).isNotNull();
        });assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }
    @Test void bootstrapDoesNotRequireAnExistingLogin()throws Exception{
        var chain=new MockFilterChain();filter.doFilter(new MockHttpServletRequest("GET","/api/auth/bootstrap"),new MockHttpServletResponse(),chain);
        assertThat(chain.getRequest()).isNotNull();verifyNoInteractions(service);
    }
}
