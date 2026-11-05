package com.agentops.api.web;

import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiBoundaryTest {
    MockMvc mvc;
    @RestController static class Probe {
        @PostMapping("/api/probe/{id}")Object input(@PathVariable UUID id,@RequestBody Map<String,Object> input){return input;}
        @GetMapping("/api/fault")Object fault(){throw new NullPointerException("secret-password-internal-location");}
        @GetMapping("/api/database")Object database(){throw new org.springframework.dao.DataAccessResourceFailureException("jdbc-password-secret");}
        @GetMapping("/api/not-found")Object missing(){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"任务不存在");}
    }
    @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new Probe()).setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestLimitsFilter()).build();}
    @Test void malformedJsonMissingBodyAndWrongIdentifiersAreClientErrors()throws Exception{
        String path="/api/probe/"+UUID.randomUUID();
        for(String body:List.of("{bad","null",""))mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        mvc.perform(post("/api/probe/not-uuid").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }
    @Test void jsonLimitDoesNotAllowLargeRequestsToReachControllers()throws Exception{
        mvc.perform(post("/api/probe/"+UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\""+"a".repeat(270000)+"\"}")).andExpect(status().isPayloadTooLarge());
    }
    @Test void chunkedJsonCannotBypassTheStreamLimit()throws Exception{
        var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/api/probe"){@Override public long getContentLengthLong(){return -1;}};
        request.setContentType("application/json");request.setContent(new byte[270000]);
        assertThatThrownBy(()->new RequestLimitsFilter().doFilter(request,new org.springframework.mock.web.MockHttpServletResponse(),(req,res)->req.getInputStream().readAllBytes())).isInstanceOf(RequestLimitsFilter.TooLarge.class);
    }
    @Test void unexpectedFailuresHaveAReferenceWithoutLeakingDetails()throws Exception{
        var r=mvc.perform(get("/api/fault")).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.requestId").isString()).andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContain("secret-password","NullPointerException","internal-location");
        mvc.perform(get("/api/database")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }
    @Test void existingHttpStatusesArePreservedInsteadOfBecoming500()throws Exception{
        mvc.perform(get("/api/not-found")).andExpect(status().isNotFound());
        mvc.perform(get("/api/probe/"+UUID.randomUUID())).andExpect(status().isMethodNotAllowed());
    }
    @Test void mainControllerUsesSharedErrorCodes()throws Exception{
        var service=org.mockito.Mockito.mock(com.agentops.api.RunApplicationService.class);
        var main=MockMvcBuilders.standaloneSetup(new ApiController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
        var failures=List.of(new com.agentops.api.RunApplicationService.NotFoundException("不存在"),
                new com.agentops.api.RunApplicationService.BadRequestException("参数有误"),
                new com.agentops.api.RunApplicationService.ConflictException("状态冲突"),
                new ApiController.ApiException(org.springframework.http.HttpStatus.FORBIDDEN,"不允许"));
        int[] codes={404,400,409,403};String[] names={"NOT_FOUND","BAD_REQUEST","CONFLICT","FORBIDDEN"};
        for(int i=0;i<failures.size();i++){
            org.mockito.Mockito.doThrow(failures.get(i)).when(service).searchSessions(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyBoolean());
            main.perform(get("/api/sessions").header("X-Tenant-Id",UUID.randomUUID()).header("X-User-Id",UUID.randomUUID()))
                    .andExpect(status().is(codes[i])).andExpect(jsonPath("$.error").value(names[i]));
        }
    }
    @Test void retiredDuplicateReadRoutesAreNotExposed()throws Exception{
        var service=org.mockito.Mockito.mock(com.agentops.api.RunApplicationService.class);
        var main=MockMvcBuilders.standaloneSetup(new ApiController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
        for(String path:List.of("/api/sessions/"+UUID.randomUUID()+"/runs/"+UUID.randomUUID(),"/api/runs/"+UUID.randomUUID()+"/progress"))
            main.perform(get(path).header("X-Tenant-Id",UUID.randomUUID()).header("X-User-Id",UUID.randomUUID())).andExpect(status().isNotFound());
        org.mockito.Mockito.verifyNoInteractions(service);
    }
}
