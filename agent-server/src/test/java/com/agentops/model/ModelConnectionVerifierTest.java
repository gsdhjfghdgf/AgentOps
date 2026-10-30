package com.agentops.model;

import com.agentops.api.RunApplicationService.BadRequestException;
import com.agentops.api.web.ModelSettingsController;
import com.agentops.domain.Primitives.Principal;
import com.agentops.infra.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import static com.agentops.TestRuns.OWNER;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelConnectionVerifierTest {
    final OpenAiChatClient client=mock(OpenAiChatClient.class);
    final Clock clock=mock(Clock.class);
    Instant now=Instant.parse("2026-10-30T00:00:00Z");
    ModelConnectionVerifier verifier;
    final ModelSettingsService.ChatConfig config=new ModelSettingsService.ChatConfig("draft","openai",
            "https://fixture.test/v1/chat/completions","model","PRIVATE_KEY_CANARY");
    @BeforeEach void setup() {
        when(clock.instant()).thenAnswer(call->now);
        verifier=new ModelConnectionVerifier(client,new Json(),clock);
        when(client.stream(any(),anyList(),anyList(),anyInt(),any(),any())).thenReturn(
                new OpenAiChatClient.ChatResult(null,List.of(new ModelPort.ToolCall("check","finish_task","{\"answer\":\"OK\"}")),null,true));
    }
    @Test void saveCheckExercisesStreamingProtocolAndCachesOnlyIdenticalCredentialsForFiveMinutes() {
        verifier.require(OWNER,config);verifier.require(OWNER,config);
        verify(client,times(1)).stream(eq(config),anyList(),anyList(),eq(512),any(),any());
        now=now.plusSeconds(301);verifier.require(OWNER,config);
        verify(client,times(2)).stream(any(),anyList(),anyList(),anyInt(),any(),any());
    }
    @Test void changedKeyModelUrlOrOwnerRequiresANewProbe() {
        verifier.require(OWNER,config);
        verifier.require(OWNER,new ModelSettingsService.ChatConfig("draft","openai",config.url(),config.model(),"different-key"));
        verifier.require(OWNER,new ModelSettingsService.ChatConfig("draft","openai",config.url(),"other-model",config.key()));
        verifier.require(OWNER,new ModelSettingsService.ChatConfig("draft","openai","https://other.test/v1",config.model(),config.key()));
        verifier.require(Principal.of(OWNER.tenantId(),new java.util.UUID(0,99)),config);
        verify(client,times(5)).stream(any(),anyList(),anyList(),anyInt(),any(),any());
    }
    @Test void malformedOrIncompleteResponseCannotPassAndErrorsDoNotExposeSecrets() {
        when(client.stream(any(),anyList(),anyList(),anyInt(),any(),any())).thenReturn(
                new OpenAiChatClient.ChatResult("plain text",List.of(),null,true));
        assertThat(verifier.test(OWNER,config).get("ok")).isEqualTo(false);
        assertThatThrownBy(()->verifier.require(OWNER,config)).isInstanceOf(BadRequestException.class).hasMessageContaining("配置未保存");
        when(client.stream(any(),anyList(),anyList(),anyInt(),any(),any())).thenThrow(
                new OpenAiChatClient.ModelUnavailableException("PRIVATE_KEY_CANARY",false,null,null,"MODEL_HTTP_401"));
        assertThat(new Json().write(verifier.test(OWNER,config))).contains("401").doesNotContain("PRIVATE_KEY_CANARY");
    }
    @Test void failedExplicitRetestInvalidatesPreviousSuccess() {
        verifier.require(OWNER,config);
        when(client.stream(any(),anyList(),anyList(),anyInt(),any(),any())).thenThrow(
                new OpenAiChatClient.ModelUnavailableException("offline",true,null,null,"MODEL_NETWORK_ERROR"));
        assertThat(verifier.test(OWNER,config).get("ok")).isEqualTo(false);
        assertThatThrownBy(()->verifier.require(OWNER,config)).isInstanceOf(BadRequestException.class);
    }
    @Test void controllerDoesNotPersistOrSelectWhenProbeFails() {
        var settings=mock(ModelSettingsService.class);var gate=mock(ModelConnectionVerifier.class);
        var update=new ModelSettingsService.ProfileUpdate("fixture",config.url(),config.key(),config.model(),false,null,true);
        when(settings.profileDraft(OWNER,null,update)).thenReturn(config);
        doThrow(new BadRequestException("配置未保存")).when(gate).require(OWNER,config);
        var controller=new ModelSettingsController(settings,gate);
        assertThatThrownBy(()->controller.createProfile(OWNER.tenantId().toString(),OWNER.userId().toString(),update))
                .isInstanceOf(BadRequestException.class);
        verify(settings,never()).createProfile(any(),any());
    }
}
