package com.agentops.tool;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class PublicWebClient {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    public record Page(String url,String content,String contentType) {}
    public static URI validate(String text) throws IOException {
        URI uri;
        try{uri=URI.create(text);}catch(RuntimeException e){throw new IllegalArgumentException("URL无效");}
        if(!java.util.Set.of("https","http").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null
                || uri.getFragment()!=null || (uri.getPort()!=-1 && uri.getPort()!=80 && uri.getPort()!=443) || text.length()>2048)
            throw new IllegalArgumentException("只支持公网 HTTP/HTTPS 页面和80/443端口");
        for(var ip:InetAddress.getAllByName(uri.getHost())) {
            byte[] bytes=ip.getAddress();int first=bytes[0]&255;
            if(ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress() || ip.isSiteLocalAddress()
                    || ip.isMulticastAddress() || (bytes.length==16 && (first&254)==252)
                    || (bytes.length==4 && first==100 && (bytes[1]&255)>=64 && (bytes[1]&255)<=127))
                throw new IllegalArgumentException("联网工具不访问本机、内网或元数据地址");
        }
        return uri;
    }
    public Page get(String url,int maximum) throws IOException,InterruptedException {
        URI uri=validate(url);
        for(int redirects=0;redirects<5;redirects++) {
            var response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
                    .header("User-Agent","Mozilla/5.0 (compatible; AgentOps/0.1; public document reader)").header("Accept-Language","zh-CN,zh;q=0.9,en;q=0.7").header("Accept","text/html,application/xhtml+xml,application/xml,text/plain,application/json,text/markdown")
                    .GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                if(response.statusCode()>=300 && response.statusCode()<400) {
                    String location=response.headers().firstValue("location").orElseThrow(()->new IOException("页面跳转缺少地址"));
                    uri=validate(uri.resolve(location).toString());continue;
                }
                if(response.statusCode()!=200)throw new IOException("网页返回 HTTP "+response.statusCode());
                byte[] bytes=stream.readNBytes(maximum+1);
                if(bytes.length>maximum)throw new IllegalArgumentException("页面超过大小限制");
                String type=response.headers().firstValue("content-type").orElse("text/plain");
                return new Page(uri.toString(),decode(bytes,type),type);
            }
        }
        throw new IOException("页面跳转次数过多");
    }
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
