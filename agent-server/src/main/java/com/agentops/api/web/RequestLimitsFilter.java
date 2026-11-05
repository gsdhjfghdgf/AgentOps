package com.agentops.api.web;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bound JSON input even for chunked requests; multipart limits remain under Spring's 20 MB policy. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE+10)
public class RequestLimitsFilter extends OncePerRequestFilter {
    static final int MAX_JSON_BYTES=262144;
    public static final class TooLarge extends IOException {public TooLarge(){super("JSON request too large");}}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){return request.getContentType()==null||!request.getContentType().toLowerCase(java.util.Locale.ROOT).contains("json");}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        if(request.getContentLengthLong()>MAX_JSON_BYTES){response.setStatus(413);response.setCharacterEncoding("UTF-8");response.setContentType("application/json");response.getWriter().write("{\"error\":\"PAYLOAD_TOO_LARGE\",\"message\":\"请求内容过大，请通过文件导入资料\"}");return;}
        var source=request.getInputStream();
        var bounded=new ServletInputStream(){
            private long count;
            private void received(int n)throws TooLarge{if(n>0&&(count+=n)>MAX_JSON_BYTES)throw new TooLarge();}
            @Override public int read()throws IOException{int b=source.read();if(b>=0)received(1);return b;}
            @Override public int read(byte[] bytes,int off,int len)throws IOException{int n=source.read(bytes,off,len);received(n);return n;}
            @Override public boolean isFinished(){return source.isFinished();}
            @Override public boolean isReady(){return source.isReady();}
            @Override public void setReadListener(ReadListener listener){source.setReadListener(listener);}
        };
        chain.doFilter(new HttpServletRequestWrapper(request){
            @Override public ServletInputStream getInputStream(){return bounded;}
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(bounded,StandardCharsets.UTF_8));}
        },response);
    }
}
