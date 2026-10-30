package com.agentops.operations;

import com.agentops.api.RunApplicationService;
import com.agentops.config.ModelKeyCipher;
import com.agentops.domain.Primitives.Principal;
import com.agentops.infra.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

/** Explicit owner-configured Streamable HTTP connections, not a model-supplied URL proxy. */
@Service
public class McpService {
    private final JdbcTemplate db;private final Json json;private final ModelKeyCipher cipher;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    public McpService(JdbcTemplate db,Json json,ModelKeyCipher cipher){this.db=db;this.json=json;this.cipher=cipher;}
    public List<Map<String,Object>> list(Principal p){return db.queryForList("SELECT id,name,url,enabled,(key_cipher<>'')AS key_configured FROM mcp_connections WHERE tenant_id=? AND user_id=? ORDER BY created_at",p.tenantId(),p.userId());}
    public record Input(String name,String url,String apiKey,boolean enabled){@Override public String toString(){return "McpInput[redacted]";}}
    public UUID save(Principal p,Input in){
        if(in.name()==null||in.name().isBlank()||in.name().length()>80||in.url()==null||in.url().length()>1000)throw new IllegalArgumentException("请提供连接名称和MCP端点");
        URI uri=URI.create(in.url());if(!Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getQuery()!=null)throw new IllegalArgumentException("MCP端点必须是无账号、查询参数和片段的HTTP(S)地址");
        if(!"https".equals(uri.getScheme())&&!Set.of("localhost","127.0.0.1","[::1]").contains(uri.getHost()))throw new IllegalArgumentException("远程MCP需要HTTPS；本机服务可使用HTTP");
        if(in.apiKey()!=null&&(in.apiKey().length()>4000||in.apiKey().contains("\n")||in.apiKey().contains("\r")))throw new IllegalArgumentException("Key无效");
        UUID id=UUID.randomUUID();String key=in.apiKey()==null?"":in.apiKey();
        db.update("INSERT INTO mcp_connections(id,tenant_id,user_id,name,url,key_cipher,enabled)VALUES(?,?,?,?,?,?,?)",id,p.tenantId(),p.userId(),in.name().strip(),in.url(),key.isEmpty()?"":cipher.encrypt(key,aad(p,id)),in.enabled());return id;
    }
    public void enabled(Principal p,UUID id,boolean enabled){if(db.update("UPDATE mcp_connections SET enabled=? WHERE tenant_id=? AND user_id=? AND id=?",enabled,p.tenantId(),p.userId(),id)!=1)throw new RunApplicationService.NotFoundException("MCP连接不存在");}
    public void delete(Principal p,UUID id){db.update("DELETE FROM mcp_connections WHERE tenant_id=? AND user_id=? AND id=?",p.tenantId(),p.userId(),id);}
    private String aad(Principal p,UUID id){return "mcp:"+p.tenantId()+":"+p.userId()+":"+id;}
    private Connection connect(Principal p,UUID id)throws Exception{
        var rows=db.queryForList("SELECT url,key_cipher FROM mcp_connections WHERE tenant_id=? AND user_id=? AND id=? AND enabled",p.tenantId(),p.userId(),id);if(rows.isEmpty())throw new RunApplicationService.NotFoundException("MCP连接不存在或已禁用");
        var row=rows.get(0);String encrypted=row.get("key_cipher").toString();var connection=new Connection(URI.create(row.get("url").toString()),encrypted.isEmpty()?"":cipher.decrypt(encrypted,aad(p,id)));
        var result=connection.request("initialize",Map.of("protocolVersion","2025-06-18","capabilities",Map.of(),"clientInfo",Map.of("name","AgentOps","version","0.1.14")),true);
        connection.version=result.path("protocolVersion").asText("2025-06-18");if(!Set.of("2025-06-18","2025-03-26","2025-11-25").contains(connection.version))throw new IllegalStateException("MCP协议版本暂不支持");
        connection.request("notifications/initialized",Map.of(),false);return connection;
    }
    public List<Object> tools(Principal p,UUID id)throws Exception{
        var c=connect(p,id);try{var result=new ArrayList<Object>();String cursor="";for(int page=0;page<20;page++){
            var response=c.request("tools/list",cursor.isEmpty()?Map.of():Map.of("cursor",cursor),true);for(var tool:response.path("tools")){result.add(json.map(tool.toString()));if(result.size()>200)throw new IllegalStateException("MCP工具过多，请缩小服务范围");}
            cursor=response.path("nextCursor").asText("");if(cursor.isEmpty())return result;
        }throw new IllegalStateException("MCP工具分页过多");}finally{c.close();}
    }
    public JsonNode call(Principal p,UUID id,String name,String arguments)throws Exception{
        if(name==null||name.isBlank()||name.length()>200)throw new IllegalArgumentException("MCP工具名无效");var args=json.tree(arguments);if(!args.isObject()||arguments.length()>24000)throw new IllegalArgumentException("MCP参数必须是JSON对象且不超过24000字");
        var c=connect(p,id);try{return c.request("tools/call",Map.of("name",name,"arguments",args),true);}finally{c.close();}
    }
    private final class Connection {
        private final URI url;private final String key;private String session="",version="2025-06-18";
        Connection(URI url,String key){this.url=url;this.key=key;}
        JsonNode request(String method,Map<String,?> params,boolean response)throws Exception{
            String id=UUID.randomUUID().toString();var body=new LinkedHashMap<String,Object>();body.put("jsonrpc","2.0");if(response)body.put("id",id);body.put("method",method);body.put("params",params);
            var builder=HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(35)).header("Content-Type","application/json").header("Accept","application/json, text/event-stream").header("MCP-Protocol-Version",version).POST(HttpRequest.BodyPublishers.ofString(json.write(body)));
            if(!session.isEmpty())builder.header("Mcp-Session-Id",session);if(!key.isEmpty())builder.header("Authorization","Bearer "+key);
            var reply=http.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=reply.body()){
                if(reply.statusCode()<200||reply.statusCode()>=300)throw new IllegalStateException("MCP服务返回HTTP "+reply.statusCode());
                reply.headers().firstValue("Mcp-Session-Id").ifPresent(value->session=value);
                if(!response)return json.tree("{}");
                boolean sse=reply.headers().firstValue("Content-Type").orElse("").contains("text/event-stream");
                var future=CompletableFuture.supplyAsync(()->{try{
                    if(!sse){byte[] bytes=stream.readNBytes(1_000_001);if(bytes.length>1_000_000)throw new IOException("MCP响应超过1MB");return envelope(json.tree(new String(bytes,StandardCharsets.UTF_8)),id);}
                    var reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8));String line;int size=0;var data=new StringBuilder();
                    while((line=reader.readLine())!=null){size+=line.length();if(size>1_000_000)throw new IOException("MCP响应超过1MB");if(line.startsWith("data:"))data.append(line.substring(5).stripLeading()).append('\n');else if(line.isBlank()&&!data.isEmpty()){var node=json.tree(data.toString());data.setLength(0);if(node.path("id").asText().equals(id))return envelope(node,id);}}
                    throw new IOException("MCP流未返回请求结果");
                }catch(IOException e){throw new UncheckedIOException(e);}});
                try{return future.get(35,TimeUnit.SECONDS);}finally{future.cancel(true);}
            }
        }
        private JsonNode envelope(JsonNode node,String id){if(!node.path("id").asText().equals(id))throw new IllegalStateException("MCP响应ID不匹配");if(node.has("error"))throw new IllegalStateException("MCP协议错误 "+node.path("error").path("code").asText());if(!node.has("result"))throw new IllegalStateException("MCP响应缺少result");return node.path("result");}
        void close(){if(!session.isEmpty())try{var b=HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(3)).header("Mcp-Session-Id",session).header("MCP-Protocol-Version",version).DELETE();if(!key.isEmpty())b.header("Authorization","Bearer "+key);http.send(b.build(),HttpResponse.BodyHandlers.discarding());}catch(Exception ignored){/* Session expiry also releases servers without DELETE support. */}}
    }
}
