package com.agentops.memory;
import com.agentops.infra.Json;
import com.agentops.model.EmbeddingRouter;
import com.agentops.persistence.MemoryRepo;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemoryServiceTest {
    @Mock MemoryRepo repo; @Mock EmbeddingRouter embedding; @Mock MemorySummarizer summarizer;
    MemoryService service;
    final UUID id=new UUID(0,88);
    final String original="以后请用中文回答";
    final List<MemorySummarizer.Source> sources=List.of(new MemorySummarizer.Source(1,original,NOW));
    @BeforeEach void setup(){service=new MemoryService(repo,embedding,new Json(),summarizer);}
    void candidate(String evidence,String content){
        when(repo.autoEnabled(OWNER.tenantId(),OWNER.userId())).thenReturn(true);
        when(summarizer.summarize(OWNER,sources)).thenReturn(List.of(new MemorySummarizer.Candidate("response.language","preference",content,1,evidence)));
    }
    @Test void automaticMemoryUsesOriginalEvidenceAndEmbedsContent() {
        candidate(original,"用户偏好中文回答");
        when(repo.upsertAutomatic(eq(OWNER.tenantId()),eq(OWNER.userId()),anyString(),anyString(),anyString(),eq(NOW))).thenReturn(Optional.of(id));
        when(repo.findVersionById(OWNER.tenantId(),id)).thenReturn(Optional.of(1L));
        when(embedding.embed("用户偏好中文回答")).thenReturn(new float[]{.1f});
        when(embedding.toLiteral(any(float[].class))).thenReturn("[0.1]");
        when(embedding.modelId()).thenReturn("test");
        assertThat(service.extractFromRun(OWNER,SESSION,sources)).isEqualTo(1);
        verify(embedding).embed("用户偏好中文回答");
        verify(repo).insertSource(OWNER.tenantId(),OWNER.userId(),id,1,SESSION,1,original);
    }
    @Test void disabledSettingSkipsExtraction() {
        assertThat(service.extractFromRun(OWNER,SESSION,sources)).isZero();
        verifyNoInteractions(summarizer,embedding);
    }
    @Test void unsupportedEvidenceIsNotSaved() {
        candidate("模型虚构的引文","用户喜欢英文");
        assertThat(service.extractFromRun(OWNER,SESSION,sources)).isZero();
        verify(repo,never()).upsertAutomatic(any(),any(),any(),any(),any(),any());
    }
    @Test void secretCandidatesAreNotStored() {
        candidate(original,"用户密码是 secret");
        assertThat(service.extractFromRun(OWNER,SESSION,sources)).isZero();
        verifyNoInteractions(embedding);
    }
    @Test void noRewriteForRevokedOrAlreadyPresentMemory() {
        candidate(original,"用户偏好中文回答");
        assertThat(service.extractFromRun(OWNER,SESSION,sources)).isZero();
        verifyNoInteractions(embedding);
    }
}
