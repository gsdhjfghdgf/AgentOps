package com.agentops.model;

import com.agentops.config.AgentProperties;
import com.agentops.domain.ModelDecision;
import com.agentops.infra.Json;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class RealModelIntegrationTest {
    private com.agentops.domain.Primitives.RunSnapshot snapshot(String goal) {
        return new com.agentops.domain.Primitives.RunSnapshot(
                new com.agentops.domain.Primitives.RunKey(com.agentops.TestRuns.OWNER,com.agentops.TestRuns.SESSION,com.agentops.TestRuns.RUN),
                com.agentops.domain.AgentDomain.RunStatus.RUNNING,com.agentops.domain.AgentDomain.Phase.MODEL,
                1,1,1,Instant.MAX,null,com.agentops.domain.Primitives.CompletionContract.defaults("chat"),
                com.agentops.domain.Primitives.WorkingState.empty(goal),null,null,null,0,com.agentops.domain.RunMode.CHAT,"");
    }
    @Test void greetingStreamsDirectAnswerWithoutToolCatalogOrQuestionnaire() {
        var client=org.mockito.Mockito.mock(OpenAiChatClient.class);
        org.mockito.Mockito.when(client.stream(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    assertThat((List<?>)call.getArgument(2)).isEmpty();
                    @SuppressWarnings("unchecked") var publish=(java.util.function.Consumer<ChatStreamAccumulator.Part>)call.getArgument(5);
                    publish.accept(new ChatStreamAccumulator.Part("commentary","你好，"));
                    publish.accept(new ChatStreamAccumulator.Part("commentary","有什么想聊的？"));
                    return new OpenAiChatClient.ChatResult("你好，有什么想聊的？",List.of(),new OpenAiChatClient.ChatUsage(25,8),true);
                });
        var parts=new java.util.ArrayList<String>();
        var result=new OpenAiModelPort(client,props,json,settings).request(snapshot("你好"),"简洁回答",
                List.of(ModelPort.ModelMessage.user("你好")),List.of(com.agentops.tool.MessagingTools.spec()),control,parts::add);
        assertThat(result.decision()).isInstanceOf(ModelDecision.FinalCandidate.class);
        assertThat(String.join("",parts)).isEqualTo("你好，有什么想聊的？");
        assertThat(result.usage().totalTokens()).isEqualTo(33);
    }
    @Test void chatWithARealQuestionCanRequestSearchTools() {
        var client=org.mockito.Mockito.mock(OpenAiChatClient.class);
        org.mockito.Mockito.when(client.stream(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new OpenAiChatClient.ChatResult("我会先查阅来源。",List.of(
                        new ModelPort.ToolCall("search","web_search","{\"query\":\"Spring Boot 最新版本\"}")),null,true));
        var result=new OpenAiModelPort(client,props,json,settings).request(snapshot("查询网络最新信息"),"可搜索",
                List.of(ModelPort.ModelMessage.user("查询网络最新信息")),List.of(),control,s->{});
        assertThat(result.decision()).isInstanceOf(ModelDecision.ToolBatch.class);
        assertThat(((ModelDecision.ToolBatch)result.decision()).calls()).extracting(com.agentops.domain.ToolTypes.ToolCallRequest::toolName)
                .containsExactly("web_search");
    }
    @Test void ordinaryChatStreamsPlainAnswerAndDoesNotRequireFinishTool() {
        var client=org.mockito.Mockito.mock(OpenAiChatClient.class);
        org.mockito.Mockito.when(client.stream(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call->{
                    @SuppressWarnings("unchecked") var tools=(List<Map<String,Object>>)call.getArgument(2);
                    assertThat(tools).noneMatch(tool->"finish_task".equals(((Map<?,?>)tool.get("function")).get("name")));
                    @SuppressWarnings("unchecked") var publish=(java.util.function.Consumer<ChatStreamAccumulator.Part>)call.getArgument(5);
                    publish.accept(new ChatStreamAccumulator.Part("commentary","张家界主要有"));
                    publish.accept(new ChatStreamAccumulator.Part("commentary","武陵源和天门山。"));
                    return new OpenAiChatClient.ChatResult("张家界主要有武陵源和天门山。",List.of(),null,true);
                });
        var parts=new java.util.ArrayList<String>();
        var result=new OpenAiModelPort(client,props,json,settings).request(snapshot("张家界有哪些风景？"),"直接回答",
                List.of(ModelPort.ModelMessage.user("张家界有哪些风景？")),List.of(),control,parts::add);
        assertThat(result.decision()).isInstanceOf(ModelDecision.FinalCandidate.class);
        assertThat(String.join("",parts)).isEqualTo("张家界主要有武陵源和天门山。");
    }
    @Test void greetingCannotCommitIncompleteOrUnexpectedToolResponse() {
        var client=org.mockito.Mockito.mock(OpenAiChatClient.class);
        org.mockito.Mockito.when(client.stream(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new OpenAiChatClient.ChatResult("不完整",List.of(),null,false),
                        new OpenAiChatClient.ChatResult(null,List.of(new ModelPort.ToolCall("write","workspace_write","{}")),null,true));
        var port=new OpenAiModelPort(client,props,json,settings);
        for(int i=0;i<2;i++)assertThatThrownBy(()->port.request(snapshot("你好"),"回答",List.of(),List.of(),control,s->{}))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private final Json json=new Json();
    private final ModelSettingsService settings=org.mockito.Mockito.mock(ModelSettingsService.class);
    @org.junit.jupiter.api.BeforeEach void settings(){org.mockito.Mockito.when(settings.forRun(org.mockito.ArgumentMatchers.any()))
            .thenReturn(ModelSettingsService.defaults(props));}
    private final AgentProperties props=new AgentProperties(new AgentProperties.Model("openai",null,"chat-secret",
            "deepseek-flash","bge-m3:latest",1024,60,2,"https://api.deepseek.com/v1/chat/completions",
            "ollama","http://127.0.0.1:11434",null),
            new AgentProperties.Budget(8,16,40000,2000,0,"USD",10),null,null,null,null,null,null);
    private final ModelPort.ExecutionControl control=new ModelPort.ExecutionControl() {
        public boolean cancellationRequested(){return false;}
        public Instant deadlineAt(){return Instant.MAX;}
    };

    @Test void chatUsesFullConfiguredEndpointAndReportsRealTokens() {
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(props.model().chatEndpoint())).andExpect(header("Authorization","Bearer chat-secret"))
                .andExpect(jsonPath("$.thinking.type").value("disabled"))
                .andExpect(jsonPath("$.tools[0].type").value("function"))
                .andExpect(jsonPath("$.tools[0].function.name").value("send_email"))
                .andExpect(jsonPath("$.tool_choice").value("required"))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[
                          {"id":"finish-1","function":{"name":"finish_task","arguments":"{\\"answer\\":\\"真实模型回答\\"}"}}]}}],
                         "usage":{"prompt_tokens":123,"completion_tokens":9}}
                        """,MediaType.APPLICATION_JSON));
        var client=new OpenAiChatClient(builder.build(),props,json);
        var port=new OpenAiModelPort(client,props,json,settings);
        var result=port.request(null,"",List.of(ModelPort.ModelMessage.user("你好")),
                List.of(com.agentops.tool.MessagingTools.spec()),control,s->{});
        assertThat(result.decision()).isInstanceOf(ModelDecision.FinalCandidate.class);
        assertThat(result.usage().totalTokens()).isEqualTo(132);
        server.verify();
    }

    @Test void ollamaUsesSeparateEndpointWithoutChatSecretAndValidatesDimensions() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        var vector=new java.util.ArrayList<Double>(java.util.Collections.nCopies(1024,0.01));
        server.expect(requestTo("http://127.0.0.1:11434/api/embed")).andExpect(headerDoesNotExist("Authorization"))
                .andExpect(jsonPath("$.model").value("bge-m3:latest"))
                .andExpect(jsonPath("$.truncate").value(false))
                .andRespond(withSuccess(json.write(Map.of("embeddings",List.of(vector))),MediaType.APPLICATION_JSON));
        var model=new OllamaEmbeddingModel(builder.build(),props,json);
        assertThat(model.embed("酒店报销标准")).hasSize(1024);
        assertThat(model.modelId()).isEqualTo("ollama:bge-m3:latest@1024");
        server.verify();
    }

    @Test void wrongDimensionIsRejectedInsteadOfPadding() {
        var builder=RestClient.builder();var server=MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andRespond(withSuccess("{\"embeddings\":[[1,2,3]]}",MediaType.APPLICATION_JSON));
        assertThatThrownBy(()->new OllamaEmbeddingModel(builder.build(),props,json).embed("测试"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("1024");
        server.verify();
    }

    @Test void truncatedToolCallsNeverExecute() {
        var client=org.mockito.Mockito.mock(OpenAiChatClient.class);
        org.mockito.Mockito.when(client.stream(org.mockito.ArgumentMatchers.any(ModelSettingsService.ChatConfig.class),org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new OpenAiChatClient.ChatResult(null,List.of(new ModelPort.ToolCall("c1","send_email","{\"to\":")),null,false));
        var result=new OpenAiModelPort(client,props,json,settings).next(null,"",List.of(),List.of(),control,s->{});
        assertThat(result).isInstanceOf(ModelDecision.AskUser.class);
    }

    @Test void clarificationPausesRunAndDoesNotBecomeFinalAnswer() {
        var client=org.mockito.Mockito.mock(OpenAiChatClient.class);
        org.mockito.Mockito.when(client.stream(org.mockito.ArgumentMatchers.any(ModelSettingsService.ChatConfig.class),org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new OpenAiChatClient.ChatResult(null,List.of(new ModelPort.ToolCall("c1","ask_user",
                        "{\"question\":\"需要查询什么？\",\"options\":[\"本月销售\",\"上月销售\"]}")),null,true));
        var result=new OpenAiModelPort(client,props,json,settings).next(null,"",List.of(),List.of(),control,s->{});
        assertThat(result).isEqualTo(new ModelDecision.AskUser(((ModelDecision.AskUser) result).questionId(),"需要查询什么？",List.of("本月销售","上月销售")));
    }

    @Test void baseUrlSupportsRootAndVersionedPaths() {
        assertThat(AgentProperties.Model.endpoint("http://localhost:11434/v1/","embeddings"))
                .isEqualTo("http://localhost:11434/v1/embeddings");
        assertThat(AgentProperties.Model.endpoint("https://example.com","chat/completions"))
                .isEqualTo("https://example.com/v1/chat/completions");
    }

    @Test void untypedClarificationCannotBeCommittedAsSuccess() {
        var client=org.mockito.Mockito.mock(OpenAiChatClient.class);
        org.mockito.Mockito.when(client.stream(org.mockito.ArgumentMatchers.any(ModelSettingsService.ChatConfig.class),org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new OpenAiChatClient.ChatResult("你想查询什么？",List.of(),null,true));
        assertThatThrownBy(()->new OpenAiModelPort(client,props,json,settings).next(null,"",List.of(),List.of(),control,s->{}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("明确行动");
    }

    @Test void finalAnswerCannotDiscardAPendingWriteInSameBatch() {
        var client=org.mockito.Mockito.mock(OpenAiChatClient.class);
        org.mockito.Mockito.when(client.stream(org.mockito.ArgumentMatchers.any(ModelSettingsService.ChatConfig.class),org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
                .thenReturn(new OpenAiChatClient.ChatResult(null,List.of(
                        new ModelPort.ToolCall("c1","finish_task","{\"answer\":\"已完成\"}"),
                        new ModelPort.ToolCall("c2","send_email","{}")),null,true));
        assertThatThrownBy(()->new OpenAiModelPort(client,props,json,settings).next(null,"",List.of(),List.of(),control,s->{}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能与其他行动混用");
    }
}
