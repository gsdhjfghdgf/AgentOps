package com.agentops.tool;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class PublicWebClient implements AutoCloseable {
    private final HttpClient client;
    private final com.agentops.browser.GuardedBrowserProxy guard;
    private final java.util.concurrent.ScheduledExecutorService deadlines=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"web-read-deadline");t.setDaemon(true);return t;});
    public PublicWebClient(){this(System.getenv().getOrDefault("HTTPS_PROXY",System.getenv().getOrDefault("HTTP_PROXY","")));}
    @org.springframework.beans.factory.annotation.Autowired
    public PublicWebClient(@org.springframework.beans.factory.annotation.Value("${agent.public-web.proxy-url:}")String proxyUrl){
        var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER);
        proxySelector(proxyUrl); // Validate trusted administrator proxy configuration before creating a listener.
        try{guard=new com.agentops.browser.GuardedBrowserProxy("",proxyUrl==null||proxyUrl.isBlank()?null:URI.create(proxyUrl));}
        catch(IOException ex){throw new IllegalStateException("无法启动受控公网连接",ex);}
        builder.proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1",URI.create(guard.address()).getPort())));client=builder.build();
    }
    static ProxySelector proxySelector(String value){
        if(value==null||value.isBlank())return null;
        try{
            var uri=URI.create(value);int port=uri.getPort()<0?80:uri.getPort();
            if(!"http".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||port<1||port>65535||!(uri.getPath()==null||uri.getPath().isEmpty()||uri.getPath().equals("/")))throw new IllegalArgumentException();
            return ProxySelector.of(new InetSocketAddress(uri.getHost(),port));
        }catch(IllegalArgumentException error){throw new IllegalArgumentException("公网代理配置无效：请使用不含账号密码的HTTP代理地址");}
    }
    public record Page(String url,String content,String contentType) {}
    public record Resource(String url,byte[] bytes,String contentType) {}
    public static URI validate(String text) throws IOException {
        URI uri=com.agentops.security.PublicNetworkPolicy.uri(text);
        com.agentops.security.PublicNetworkPolicy.resolve(uri);
        return uri;
    }
    public Page get(String url,int maximum) throws IOException,InterruptedException {
        var resource=fetch(url,maximum,"text/html,application/xhtml+xml,application/xml,text/plain,application/json,text/markdown");
        return new Page(resource.url(),decode(resource.bytes(),resource.contentType()),resource.contentType());
    }
    public Resource downloadImage(String url,int maximum) throws IOException,InterruptedException {
        return fetch(url,maximum,"image/png,image/jpeg");
    }
    private Resource fetch(String url,int maximum,String accept) throws IOException,InterruptedException {
        if(maximum<1||maximum>10_000_000)throw new IllegalArgumentException("网络响应大小限制无效");
        URI uri=validate(url);
        for(int redirects=0;redirects<5;redirects++) {
            var response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
                    .header("User-Agent","Mozilla/5.0 (compatible; AgentOps/0.1; public document reader)").header("Accept-Language","zh-CN,zh;q=0.9,en;q=0.7").header("Accept",accept)
                    .GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                var timedOut=new java.util.concurrent.atomic.AtomicBoolean();
                var closing=deadlines.schedule(()->{timedOut.set(true);try{stream.close();}catch(IOException ignored){}},12,java.util.concurrent.TimeUnit.SECONDS);
                try{
                if(response.statusCode()>=300 && response.statusCode()<400) {
                    String location=response.headers().firstValue("location").orElseThrow(()->new IOException("页面跳转缺少地址"));
                    uri=validate(uri.resolve(location).toString());continue;
                }
                if(response.statusCode()!=200)throw new IOException("网页返回 HTTP "+response.statusCode());
                byte[] bytes=stream.readNBytes(maximum+1);
                if(timedOut.get())throw new IOException("网页正文读取超时");
                if(bytes.length>maximum)throw new IllegalArgumentException("页面超过大小限制");
                String type=response.headers().firstValue("content-type").orElse("text/plain");
                return new Resource(uri.toString(),bytes,type);
                }finally{closing.cancel(false);}
            }
        }
        throw new IOException("页面跳转次数过多");
    }
    @jakarta.annotation.PreDestroy public void close(){guard.close();deadlines.shutdownNow();}
    static String decode(byte[] bytes,String type){
        var charset=StandardCharsets.UTF_8;var pattern=java.util.regex.Pattern.compile("(?i)charset\\s*=\\s*[\"']?\\s*([a-z0-9_-]+)");
        var match=pattern.matcher(type);String name=match.find()?match.group(1):"";
        if(name.isEmpty()&&type.toLowerCase(java.util.Locale.ROOT).contains("html")){
            String start=new String(bytes,0,Math.min(bytes.length,8192),StandardCharsets.ISO_8859_1);var meta=java.util.regex.Pattern.compile("(?is)<meta\\b[^>]*>").matcher(start);
            while(meta.find()){match=pattern.matcher(meta.group());if(match.find()){name=match.group(1);break;}}
        }
        if(!name.isBlank())try{charset=java.nio.charset.Charset.forName(name.equalsIgnoreCase("gb2312")||name.equalsIgnoreCase("gbk")?"GB18030":name);}catch(java.nio.charset.IllegalCharsetNameException|java.nio.charset.UnsupportedCharsetException ignored){}
        return new String(bytes,charset).replaceFirst("^\\uFEFF","");
    }
}
