package com.agentops.api.web;

import com.agentops.api.RunApplicationService;
import com.agentops.persistence.ArtifactRepo;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static com.agentops.TestRuns.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RunSyncControllerTest {
    final RunApplicationService app=mock(RunApplicationService.class);
    final ArtifactRepo artifacts=mock(ArtifactRepo.class);
    @Test void combinesOwnedStateIncrementalMessagesAndArtifactsInOneResponse() throws Exception {
        var run=new RunApplicationService.RunView(RUN.toString(),SESSION.toString(),"running","model",
                null,null,null,NOW,null,100,"chat","");
        when(app.getRun(OWNER,RUN)).thenReturn(run);
        when(app.messageDelta(OWNER,SESSION,8,100)).thenReturn(new RunApplicationService.MessageDelta(List.of(
                new RunApplicationService.MessageView(9,"assistant","new",RUN.toString(),null,NOW,List.of(),"candidate","a")),false));
        var mvc=MockMvcBuilders.standaloneSetup(new RunSyncController(app,artifacts)).build();
        mvc.perform(get("/api/runs/"+RUN+"/sync?afterSeq=8")
                .header("X-Tenant-Id",OWNER.tenantId()).header("X-User-Id",OWNER.userId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.run.mode").value("chat"))
                .andExpect(jsonPath("$.messages[0].seq").value(9)).andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.artifacts").isArray());
        verify(artifacts).listReadyByOwnerRun(OWNER.tenantId(),OWNER.userId(),RUN);
    }
    @Test void nonexistentOrForeignRunsAreRejectedBeforeAnyRelatedDataRead() throws Exception {
        when(app.getRun(OWNER,RUN)).thenThrow(new RunApplicationService.NotFoundException("任务不存在"));
        var mvc=MockMvcBuilders.standaloneSetup(new RunSyncController(app,artifacts))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/runs/"+RUN+"/sync").header("X-Tenant-Id",OWNER.tenantId()).header("X-User-Id",OWNER.userId()))
                .andExpect(status().isNotFound());
        verifyNoInteractions(artifacts);
        verify(app,never()).messageDelta(any(),any(),anyLong(),anyInt());
    }
}
