package com.agentops.context;

import com.agentops.infra.Json;
import com.agentops.model.ModelPort.ModelMessage;
import java.util.*;

/** Bounded transcript blocks. Tool intent/results stay together; user requirements never get truncated. */
public final class ContextWindow {
    private static final Set<String> READS=Set.of("project_read","workspace_read","web_read","knowledge_read");
    private final Json json;
    public ContextWindow(Json json) { this.json=json; }

    public List<ModelMessage> fit(List<ModelMessage> input,int budget,String goal) {
        if(input.isEmpty()) return List.of();
        var names=new HashMap<String,String>();
        var messageNames=new IdentityHashMap<ModelMessage,String>();
        var compact=new ArrayList<ModelMessage>();
        for(int i=0;i<input.size();i++) {
            var message=input.get(i);
            if(!"tool".equals(message.role())) {
                names.clear();
                for(var call:message.toolCalls())names.put(call.id(),call.name());
            }
            String name=names.get(message.toolCallId());
            var selected=i<input.size()-8 ? compactRead(message,name,1200) : message;
            messageNames.put(selected,name);
            compact.add(selected);
        }
        var blocks=new ArrayList<List<ModelMessage>>();
        for(var message:compact) {
            if("tool".equals(message.role()) && !blocks.isEmpty()
                    && blocks.get(blocks.size()-1).stream().flatMap(m->m.toolCalls().stream())
                    .anyMatch(call->call.id().equals(message.toolCallId())))
                blocks.get(blocks.size()-1).add(message);
            else { var block=new ArrayList<ModelMessage>();block.add(message);blocks.add(block); }
        }
        int latestUser=-1,goalBlock=-1;
        for(int i=0;i<blocks.size();i++) for(var m:blocks.get(i))
            if("user".equals(m.role())) { latestUser=i;if(Objects.equals(goal,m.content()))goalBlock=i; }
        var keep=new TreeMap<Integer,List<ModelMessage>>();
        int used=0;
        for(int index:new java.util.LinkedHashSet<>(List.of(latestUser,goalBlock))) {
            if(index<0)continue;
            keep.put(index,blocks.get(index));used+=cost(blocks.get(index));
        }
        for(int i=blocks.size()-1;i>=0;i--) {
            if(keep.containsKey(i))continue;
            List<ModelMessage> block=blocks.get(i);
            int available=budget-used;
            if(cost(block)>available) {
                int chars=Math.max(100,Math.min(1200,available/2));
                block=block.stream().map(m->compactRead(m,messageNames.get(m),chars)).toList();
            }
            int size=cost(block);
            if(size<=available) { keep.put(i,block);used+=size; }
        }
        return keep.values().stream().flatMap(Collection::stream).toList();
    }

    private int cost(List<ModelMessage> messages) {
        return messages.stream().mapToInt(m->TokenEstimate.of(m.content())+TokenEstimate.of(json.write(m.toolCalls()))+8).sum();
    }
    private ModelMessage compactRead(ModelMessage message,String name,int chars) {
        if(!"tool".equals(message.role()) || name==null || !READS.contains(name)
                || message.content()==null || message.content().length()<=chars+800) return message;
        try {
            var data=json.map(message.content());
            // Preserve SHA, source URL, status, offsets and all other evidence metadata.
            if(data.get("content") instanceof String body && body.length()>chars) {
                data.put("content",body.substring(0,chars));
                data.put("contextNotice","正文摘录；完整原文保留于会话历史，可用conversation_history或原读取工具继续核对。");
                data.put("originalContentChars",body.length());
                return ModelMessage.toolResult(message.toolCallId(),json.write(data));
            }
        } catch(RuntimeException ignored) { /* Unknown formats and mutation outcomes are never rewritten. */ }
        return message;
    }
}
