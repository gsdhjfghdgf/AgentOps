package com.agentops.model;

import com.agentops.config.AgentProperties;
import com.agentops.infra.Json;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class StreamingChatTest {
    final Json json=new Json();
    final AgentProperties props=new AgentProperties(new AgentProperties.Model("openai",null,"","fixture","bge-m3:latest",1024,20,0,"http://localhost/v1/chat/completions","ollama","http://localhost:11434",null),new AgentProperties.Budget(8,16,40000,2000,0,"USD",10),null,null,null,null,null,null);
    ModelPort.ExecutionControl control(AtomicBoolean cancelled){return new ModelPort.ExecutionControl(){public boolean cancellationRequested(){return cancelled.get();}public Instant deadlineAt(){return Instant.MAX;}};}

    @Test void partialJsonNeverLeaksEscapesOrNestedAnswers(){
        String target="中文\n引号\"和斜杠\\，emoji 😀",encoded="{\"meta\":{\"answer\":\"不要泄漏\"},\"answer\":"+json.write(target)+"}";
        String previous="";for(int i=0;i<=encoded.length();i++){String current=AnswerPrefix.read(encoded.substring(0,i));assertThat(current).startsWith(previous);assertThat(target).startsWith(current);previous=current;}
        assertThat(previous).isEqualTo(target);
        assertThat(AnswerPrefix.read("{\"answer\":\"\\u4e2d\\u6587\\ud83d\\ude00\"}")).isEqualTo("中文😀");
        assertThat(AnswerPrefix.read("{\"answer\":\"好\\u4e")).isEqualTo("好");
        assertThat(AnswerPrefix.read("{\"meta\":{\"answer\":\"nested\"}")).isEmpty();
    }

    @Test void usageAndToolArgumentsAssembleWhilePrivateReasoningIsDiscarded(){
        var accumulator=new ChatStreamAccumulator();var parts=new ArrayList<ChatStreamAccumulator.Part>();
        accumulator.accept(json.tree("{\"choices\":[{\"delta\":{\"reasoning_content\":\"PRIVATE_CANARY\",\"content\":\"准备核对\"}}]}"),parts::add);
        String args=json.write(Map.of("answer","已经核对\n中文😀"));
        for(int i=0;i<args.length();i++)accumulator.accept(json.tree(chunk(i==0?"call-1":null,i==0?"finish_task":null,args.substring(i,i+1))),parts::add);
        accumulator.accept(json.tree("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":123,\"completion_tokens\":27}}"),parts::add);
        var result=accumulator.finish();assertThat(result.complete()).isTrue();assertThat(result.usage().promptTokens()).isEqualTo(123);
        assertThat(result.toolCalls()).hasSize(1);assertThat(result.toolCalls().get(0).argumentsJson()).isEqualTo(args);
        assertThat(parts.stream().filter(p->p.channel().equals("answer")).map(ChatStreamAccumulator.Part::text).reduce("",String::concat)).isEqualTo("已经核对\n中文😀");
        assertThat(result.content()).isEqualTo("准备核对");assertThat(parts.toString()).doesNotContain("PRIVATE_CANARY");
    }

    @Test void missingFinishMarkerCannotCommitToolCalls(){
        var accumulator=new ChatStreamAccumulator();accumulator.accept(json.tree(chunk("c","send_email","{\"to\":\"a@b.com\"}")),p->{});
        assertThat(accumulator.finish().complete()).isFalse();
        accumulator.accept(json.tree("{\"choices\":[{\"finish_reason\":\"length\",\"delta\":{}}]}"),p->{});
        assertThat(accumulator.finish().complete()).isFalse();
    }
    @Test void serviceErrorsUseSafeTypedCodesWithoutExposingProviderPayload(){
        var accumulator=new ChatStreamAccumulator();
        assertThatThrownBy(()->accumulator.accept(json.tree("{\"error\":{\"message\":\"PRIVATE_CANARY\"}}"),p->{}))
                .isInstanceOfSatisfying(OpenAiChatClient.ModelUnavailableException.class,e->{
                    assertThat(e.code()).isEqualTo("MODEL_STREAM_ERROR");assertThat(e.retryable()).isTrue();
                    assertThat(e.getMessage()).doesNotContain("PRIVATE_CANARY");
                });
    }

    @Test void realHttpSsePublishesBeforeProviderCompletesAndRetainsUsage() throws Exception {
        var first=new CountDownLatch(1);var finish=new CountDownLatch(1);var received=new AtomicReference<String>();
        var server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);var pool=Executors.newSingleThreadExecutor();
        server.createContext("/v1/chat/completions",exchange->{try{
            received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));exchange.getResponseHeaders().set("Content-Type","text/event-stream; charset=utf-8");exchange.sendResponseHeaders(200,0);
            write(exchange,"{\"choices\":[{\"delta\":{\"content\":\"正在核对事实\",\"reasoning_content\":\"PRIVATE_CANARY\"}}]}");
            write(exchange,chunk("call-1","finish_task","{\"answer\":\"第一段中文"));
            if(!finish.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test release missing");
            write(exchange,chunk(null,null,"，第二段😀\"}"));write(exchange,"{\"choices\":[{\"finish_reason\":\"tool_calls\",\"delta\":{}}]}");
            write(exchange,"{\"choices\":[],\"usage\":{\"prompt_tokens\":99,\"completion_tokens\":40}}");write(exchange,"[DONE]");
        }catch(Exception e){throw new RuntimeException(e);}finally{exchange.close();}});server.start();
        var client=client();var parts=new CopyOnWriteArrayList<ChatStreamAccumulator.Part>();
        try{
            var future=pool.submit(()->client.stream(config(server),List.of(ModelPort.ModelMessage.user("test")),List.of(),1000,control(new AtomicBoolean()),part->{parts.add(part);if(part.channel().equals("answer"))first.countDown();}));
            assertThat(first.await(5,TimeUnit.SECONDS)).isTrue();assertThat(future.isDone()).isFalse();
            assertThat(parts.stream().filter(p->p.channel().equals("answer")).map(ChatStreamAccumulator.Part::text).reduce("",String::concat)).isEqualTo("第一段中文");
            finish.countDown();var result=future.get(5,TimeUnit.SECONDS);assertThat(result.complete()).isTrue();assertThat(result.usage().completionTokens()).isEqualTo(40);
            assertThat(json.tree(result.toolCalls().get(0).argumentsJson()).path("answer").asText()).isEqualTo("第一段中文，第二段😀");
            assertThat(parts.toString()).doesNotContain("PRIVATE_CANARY");assertThat(json.tree(received.get()).path("stream").asBoolean()).isTrue();assertThat(json.tree(received.get()).path("stream_options").path("include_usage").asBoolean()).isTrue();
        }finally{finish.countDown();client.closeStreams();server.stop(0);pool.shutdownNow();}
    }

    @Test void cancellationClosesAStalledStreamAndClearsInterruptForDatabaseCleanup()throws Exception{
        var first=new CountDownLatch(1);var release=new CountDownLatch(1);var cancelled=new AtomicBoolean();var interruptAtExit=new AtomicBoolean(true);
        var server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);var pool=Executors.newSingleThreadExecutor();
        server.createContext("/v1/chat/completions",exchange->{try{exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);write(exchange,chunk("c","finish_task","{\"answer\":\"尚未结束"));release.await(5,TimeUnit.SECONDS);}catch(Exception ignored){}finally{exchange.close();}});server.start();var client=client();
        try{
            var future=pool.submit(()->{try{return client.stream(config(server),List.of(),List.of(),1000,control(cancelled),part->first.countDown());}finally{interruptAtExit.set(Thread.currentThread().isInterrupted());}});
            assertThat(first.await(5,TimeUnit.SECONDS)).isTrue();cancelled.set(true);
            assertThatThrownBy(()->future.get(3,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ModelPort.Cancelled.class);
            assertThat(interruptAtExit).isFalse();
        }finally{release.countDown();client.closeStreams();server.stop(0);pool.shutdownNow();}
    }
    OpenAiChatClient client(){var factory=new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());factory.setReadTimeout(Duration.ofSeconds(20));return new OpenAiChatClient(RestClient.builder().requestFactory(factory).build(),props,json);}

    @Test void anIdleBodyIsAbortedAndInterruptIsClearedWithoutCommittingPartialTools() throws Exception {
        var nanos=new AtomicLong();var release=new CountDownLatch(1);
        var server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{try{
            exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
            write(exchange,chunk("c","finish_task","{\"answer\":\"partial"));release.await(5,TimeUnit.SECONDS);
        }catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
        var rest=RestClient.builder().requestFactory(new JdkClientHttpRequestFactory()).build();
        var client=new OpenAiChatClient(rest,rest,props,json,nanos::get);
        try {
            assertThatThrownBy(()->client.stream(config(server),List.of(),List.of(),1000,control(new AtomicBoolean()),p->nanos.set(TimeUnit.SECONDS.toNanos(91))))
                    .isInstanceOfSatisfying(OpenAiChatClient.ModelUnavailableException.class,e->assertThat(e.code()).isEqualTo("MODEL_TIMEOUT"));
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        }finally{release.countDown();client.closeStreams();server.stop(0);}
    }

    @Test void nativeHttpTimeoutIsReportedAsTimeoutEvenWhenTheWatchdogHasNotExpired() {
        var builder=RestClient.builder();var server=org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything())
                .andRespond(request->{throw new java.net.http.HttpTimeoutException("PRIVATE_CANARY");});
        var client=new OpenAiChatClient(builder.build(),props,json);
        try {
            assertThatThrownBy(()->client.stream(new ModelSettingsService.ChatConfig(null,"openai","http://fixture/v1/chat/completions","fixture",""),List.of(),List.of(),1000,control(new AtomicBoolean()),p->{}))
                .isInstanceOfSatisfying(OpenAiChatClient.ModelUnavailableException.class,e->{assertThat(e.code()).isEqualTo("MODEL_TIMEOUT");assertThat(e.getMessage()).doesNotContain("PRIVATE_CANARY");});
            server.verify();assertThat(Thread.currentThread().isInterrupted()).isFalse();
        }finally{client.closeStreams();}
    }
    ModelSettingsService.ChatConfig config(HttpServer server){return new ModelSettingsService.ChatConfig(null,"openai","http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions","fixture","");}
    String chunk(String id,String name,String args){var call=new LinkedHashMap<String,Object>();call.put("index",0);if(id!=null)call.put("id",id);var fn=new LinkedHashMap<String,Object>();if(name!=null)fn.put("name",name);fn.put("arguments",args);call.put("function",fn);return json.write(Map.of("choices",List.of(Map.of("index",0,"delta",Map.of("tool_calls",List.of(call))))));}
    void write(com.sun.net.httpserver.HttpExchange exchange,String payload)throws java.io.IOException{exchange.getResponseBody().write(("data: "+payload+"\n\n").getBytes(StandardCharsets.UTF_8));exchange.getResponseBody().flush();}
}
