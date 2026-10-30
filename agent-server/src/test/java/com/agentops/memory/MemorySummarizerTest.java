package com.agentops.memory;
import com.agentops.config.AgentProperties;
import com.agentops.infra.Json;
import com.agentops.model.OpenAiChatClient;
import org.junit.jupiter.api.Test;
import java.util.List;
import static com.agentops.TestRuns.NOW;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MemorySummarizerTest {
    AgentProperties properties(String provider) {
        return new AgentProperties(new AgentProperties.Model(provider,"http://test/v1","","test","",1024,30,1),null,null,null,null,null,null,null);
    }
    com.agentops.model.ModelSettingsService settings(String provider) {
        var settings=mock(com.agentops.model.ModelSettingsService.class);
        when(settings.current(any())).thenReturn(com.agentops.model.ModelSettingsService.defaults(properties(provider)));
        return settings;
    }
    @Test void modelModeParsesStructuredSummariesInsteadOfUsingKeywordRules() {
        var client=mock(OpenAiChatClient.class);
        when(client.chat(any(com.agentops.model.ModelSettingsService.ChatConfig.class),anyList(),isNull(),eq(1500))).thenReturn(new OpenAiChatClient.ChatResult(
                "[{\"key\":\"project.language\",\"kind\":\"fact\",\"content\":\"项目使用Java\",\"sourceSeq\":8,\"evidence\":\"项目使用Java\"}]",List.of(),null,true));
        var summarizer=new MemorySummarizer(properties("openai"),client,new Json(),settings("openai"));
        var result=summarizer.summarize(com.agentops.TestRuns.OWNER,List.of(new MemorySummarizer.Source(8,"项目使用Java",NOW)));
        assertThat(result).hasSize(1);assertThat(result.get(0).content()).isEqualTo("项目使用Java");
        assertThat(result.get(0).sourceSeq()).isEqualTo(8);
    }
    @Test void demoRulesAreDeterministicAndDoNotCallExternalModels() {
        var client=mock(OpenAiChatClient.class);
        var summarizer=new MemorySummarizer(properties("mock"),client,new Json(),settings("mock"));
        var sources=List.of(new MemorySummarizer.Source(1,"以后请用中文回答",NOW));
        assertThat(summarizer.summarize(com.agentops.TestRuns.OWNER,sources)).isEqualTo(summarizer.summarize(com.agentops.TestRuns.OWNER,sources)).hasSize(1);
        verifyNoInteractions(client);
    }
    @Test void greetingsDoNotBecomeMemories() {
        var summarizer=new MemorySummarizer(properties("mock"),mock(OpenAiChatClient.class),new Json(),settings("mock"));
        assertThat(summarizer.summarize(com.agentops.TestRuns.OWNER,List.of(new MemorySummarizer.Source(1,"你好，谢谢",NOW)))).isEmpty();
    }
}
