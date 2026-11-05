package com.agentops.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.Consumer;

/** Public content and finish_task.answer only. Provider private reasoning is deliberately ignored. */
final class ChatStreamAccumulator {
    record Part(String channel,String text){}
    private static final class Call {String id="";final StringBuilder name=new StringBuilder(),arguments=new StringBuilder();int emitted;}
    private final Map<Integer,Call> calls=new TreeMap<>();
    private final StringBuilder content=new StringBuilder();
    private OpenAiChatClient.ChatUsage usage;private String finish;
    void accept(JsonNode event,Consumer<Part> publish){
        if(event.hasNonNull("error"))throw new OpenAiChatClient.ModelUnavailableException(
                "模型流返回服务端错误，未执行工具",true,null,null,"MODEL_STREAM_ERROR");
        if(event.hasNonNull("usage")){var u=event.path("usage");usage=new OpenAiChatClient.ChatUsage(u.path("prompt_tokens").asLong(),u.path("completion_tokens").asLong());}
        for(var choice:event.path("choices")){if(choice.path("index").asInt(0)!=0)continue;
            if(choice.hasNonNull("finish_reason"))finish=choice.path("finish_reason").asText();
            var delta=choice.path("delta");if(delta.path("content").isTextual()){String text=delta.path("content").asText();content.append(text);if(!text.isEmpty())publish.accept(new Part("commentary",text));}
            for(var node:delta.path("tool_calls")){int index=node.path("index").asInt(0);if(index<0||index>127)throw new IllegalArgumentException("工具调用序号无效");var call=calls.computeIfAbsent(index,k->new Call());
                if(node.hasNonNull("id")){String id=node.path("id").asText();if(call.id.isEmpty())call.id=id;else if(!call.id.equals(id))call.id+=id;}
                var fn=node.path("function");if(fn.path("name").isTextual()){String part=fn.path("name").asText();if(!part.equals(call.name.toString()))call.name.append(part);}
                if(fn.path("arguments").isTextual())call.arguments.append(fn.path("arguments").asText());
                if(call.arguments.length()>1_000_000)throw new IllegalArgumentException("工具参数超过上限");
                if(call.name.toString().equals("finish_task")){String prefix=AnswerPrefix.read(call.arguments.toString());if(prefix.length()>call.emitted){publish.accept(new Part("answer",prefix.substring(call.emitted)));call.emitted=prefix.length();}}
            }
        }
    }
    OpenAiChatClient.ChatResult finish(){
        var result=new ArrayList<ModelPort.ToolCall>();for(var call:calls.values())result.add(new ModelPort.ToolCall(call.id,call.name.toString(),call.arguments.toString()));
        return new OpenAiChatClient.ChatResult(content.isEmpty()?null:content.toString(),result,usage,"stop".equals(finish)||"tool_calls".equals(finish),finish);
    }
}
