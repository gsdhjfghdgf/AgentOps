package com.agentops.model;

import com.agentops.config.AgentProperties;
import com.agentops.config.ModelKeyCipher;
import com.agentops.infra.Json;
import com.agentops.persistence.ModelSettingsRepo;
import com.agentops.persistence.RunRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelSettingsServiceTest {
    @TempDir Path directory;
    ModelSettingsRepo repo;
    ModelKeyCipher cipher;
    ModelSettingsService service;
    final AgentProperties props=new AgentProperties(new AgentProperties.Model("openai","https://default.test/v1",
            "system-secret","default-model","fixed-vector",1024,30,1),null,null,null,null,null,null,null);
    @BeforeEach void setup(){
        repo=mock(ModelSettingsRepo.class);cipher=new ModelKeyCipher(directory.resolve("key").toString());
        service=new ModelSettingsService(repo,cipher,props,mock(RunRepo.class),new Json());
    }
    @Test void urlsAcceptRootVersionedAndFullEndpoint() {
        assertThat(ModelSettingsService.normalizeUrl("https://example.test/")).isEqualTo("https://example.test/v1/chat/completions");
        assertThat(ModelSettingsService.normalizeUrl("https://example.test/api/v3/")).isEqualTo("https://example.test/api/v3/chat/completions");
        assertThat(ModelSettingsService.normalizeUrl("http://localhost:11434/v1/chat/completions")).isEqualTo("http://localhost:11434/v1/chat/completions");
    }
    @Test void invalidUrlsCannotHideCredentialsOrQuerySecrets() {
        for(String input:java.util.List.of("file:///etc/passwd","https://user:pass@example.test","https://example.test?key=x","https://example.test/#secret","https://example.test/\r\nX:1"))
            assertThatThrownBy(()->ModelSettingsService.normalizeUrl(input)).isInstanceOf(com.agentops.api.RunApplicationService.BadRequestException.class);
    }
    @Test void blankKeyPreservesExistingOnlyForSameEndpoint() {
        when(repo.active(OWNER)).thenReturn(Optional.empty());
        var draft=service.draft(OWNER,new ModelSettingsService.Update("https://default.test/v1","","another-model",false));
        assertThat(draft.key()).isEqualTo("system-secret");
        assertThatThrownBy(()->service.draft(OWNER,new ModelSettingsService.Update("https://other.test/v1","","another-model",false)))
                .hasMessageContaining("重新输入 Key");
    }
    @Test void keylessServiceNeverReceivesPreviousSecret() {
        var draft=service.draft(OWNER,new ModelSettingsService.Update("http://localhost:11434/v1","","local-model",true));
        assertThat(draft.key()).isEmpty();
    }
    @Test void encryptedKeysSurviveRestartAndRejectAnotherOwner() {
        String value=cipher.encrypt("sensitive-secret","owner-a");
        assertThat(value).doesNotContain("sensitive-secret");
        var reopened=new ModelKeyCipher(directory.resolve("key").toString());
        assertThat(reopened.decrypt(value,"owner-a")).isEqualTo("sensitive-secret");
        assertThatThrownBy(()->reopened.decrypt(value,"owner-b")).isInstanceOf(IllegalStateException.class);
    }
    @Test void secretNeverAppearsInViewsOrRecordLogging() {
        when(repo.active(OWNER)).thenReturn(Optional.empty());
        String view=new Json().write(service.view(OWNER));
        assertThat(view).contains("keyConfigured").doesNotContain("system-secret").doesNotContain("apiKey");
        assertThat(ModelSettingsService.defaults(props).toString()).doesNotContain("system-secret");
        assertThat(new ModelSettingsService.Update("url","sensitive-secret","model",false).toString()).doesNotContain("sensitive-secret");
    }
    @Test void savedProfilesContainOnlyCiphertextAndNoVectorSettings() {
        when(repo.active(OWNER)).thenReturn(Optional.empty());
        service.save(OWNER,new ModelSettingsService.Update("https://other.test/v1","new-secret","new-model",false));
        var profile=org.mockito.ArgumentCaptor.forClass(ModelSettingsRepo.Profile.class);
        verify(repo).save(eq(OWNER),profile.capture());
        assertThat(profile.getValue().keyCipher()).doesNotContain("new-secret");
        assertThat(cipher.decrypt(profile.getValue().keyCipher(),OWNER.tenantId()+":"+OWNER.userId()+":"+profile.getValue().id()))
                .isEqualTo("new-secret");
        assertThat(props.model().embeddingModel()).isEqualTo("fixed-vector");
    }
}
