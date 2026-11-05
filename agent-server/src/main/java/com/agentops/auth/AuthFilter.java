package com.agentops.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.*;

/** Authenticate every API route, including downloads and SSE, then inject server-owned identities. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE+20)
public class AuthFilter extends OncePerRequestFilter {
    public static final String ACCOUNT="agent.authenticated";
    private final AuthService auth;private final ObjectMapper json;
    private final com.agentops.security.TrafficGuard traffic;
    public AuthFilter(AuthService auth,ObjectMapper json){this(auth,json,null);}
    @org.springframework.beans.factory.annotation.Autowired
    public AuthFilter(AuthService auth,ObjectMapper json,com.agentops.security.TrafficGuard traffic){this.auth=auth;this.json=json;this.traffic=traffic;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        String path=request.getRequestURI().substring(request.getContextPath().length());
        // Fail closed: encoded/matrix paths must not bypass authentication before Spring normalizes them.
        return path.equals("/actuator/health");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        response.setHeader("Cache-Control","no-store");
        String path=request.getRequestURI().substring(request.getContextPath().length());
        if(Set.of("/api/auth/bootstrap","/api/auth/register","/api/auth/login").contains(path)){chain.doFilter(request,response);return;}
        AuthService.Authenticated user;
        try{
            String header=request.getHeader("Authorization");
            user=auth.authenticate(header!=null&&header.startsWith("Bearer ")?header.substring(7):null);
        }catch(ResponseStatusException ex){reject(response,ex.getStatusCode().value(),"UNAUTHORIZED",ex.getReason());return;}
        catch(org.springframework.dao.DataAccessException ex){reject(response,503,"SERVICE_UNAVAILABLE","账号服务暂不可用，请稍后重试");return;}
        request.setAttribute(ACCOUNT,user);
        if(traffic!=null&&!traffic.allow("account:"+user.account().id(),360)){response.setHeader("Retry-After","60");reject(response,429,"TOO_MANY_REQUESTS","请求过于频繁，请稍后再试");return;}
        if(traffic!=null&&(path.contains("/slides/")||path.endsWith("/preview")||path.endsWith("/export"))&&!traffic.allow("render:"+user.account().id(),30)){response.setHeader("Retry-After","60");reject(response,429,"TOO_MANY_REQUESTS","预览或导出过于频繁，请稍后再试");return;}
        var lease=traffic!=null&&path.endsWith("/events")?traffic.acquireStream(user.account().id()):null;
        if(traffic!=null&&path.endsWith("/events")&&lease==null){reject(response,429,"TOO_MANY_STREAMS","实时连接过多，请关闭其他窗口后重试");return;}
        response.setHeader("Cache-Control","no-store");
        try{chain.doFilter(new HttpServletRequestWrapper(request){
            @Override public String getHeader(String name){return switch(name.toLowerCase(Locale.ROOT)){
                case "x-tenant-id"->user.account().tenantId().toString();case "x-user-id"->user.account().userId().toString();default->super.getHeader(name);};}
            @Override public Enumeration<String> getHeaders(String name){return name.equalsIgnoreCase("X-Tenant-Id")||name.equalsIgnoreCase("X-User-Id")?Collections.enumeration(List.of(getHeader(name))):super.getHeaders(name);}
            @Override public Enumeration<String> getHeaderNames(){var names=new LinkedHashSet<>(Collections.list(super.getHeaderNames()));names.add("X-Tenant-Id");names.add("X-User-Id");return Collections.enumeration(names);}
        },response);
        }finally{
            if(lease!=null){
                if(request.isAsyncStarted())try{request.getAsyncContext().addListener(new AsyncListener(){
                    public void onComplete(AsyncEvent e){lease.close();}public void onTimeout(AsyncEvent e){lease.close();}public void onError(AsyncEvent e){lease.close();}public void onStartAsync(AsyncEvent e){e.getAsyncContext().addListener(this);}
                });}catch(IllegalStateException completed){lease.close();}
                else lease.close();
            }
        }
    }
    private void reject(HttpServletResponse response,int status,String code,String text)throws IOException{
        response.setStatus(status);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");response.setHeader("Cache-Control","no-store");
        json.writeValue(response.getOutputStream(),Map.of("error",code,"message",text==null?"请重新登录":text));
    }
}
