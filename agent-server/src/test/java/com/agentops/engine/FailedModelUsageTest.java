package com.agentops.engine;

import com.agentops.model.OpenAiChatClient;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FailedModelUsageTest {
    @Test void truncatedResponsesRetainKnownProviderUsageRatherThanChargingAnUnknownEstimate() {
        var error=new OpenAiChatClient.ModelUnavailableException("safe",true,null,null,"MODEL_OUTPUT_TRUNCATED",new OpenAiChatClient.ChatUsage(29000,2000));
        var usage=AgentEngine.reportedFailureUsage(new RuntimeException(error));
        assertThat(usage.totalTokens()).isEqualTo(31000);
        assertThat(usage.finalUsageKnown()).isTrue();
    }
    @Test void transportFailuresWithoutProviderUsageRemainUnknown() {
        assertThat(AgentEngine.reportedFailureUsage(new OpenAiChatClient.ModelUnavailableException("safe",true,null,null,"MODEL_TIMEOUT"))).isNull();
    }
}
