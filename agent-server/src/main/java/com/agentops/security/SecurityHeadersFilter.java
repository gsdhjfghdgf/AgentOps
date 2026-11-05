package com.agentops.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

@Component @Order(Ordered.HIGHEST_PRECEDENCE+5)
public class SecurityHeadersFilter extends OncePerRequestFilter {
    private final TrafficGuard traffic;
    public SecurityHeadersFilter(TrafficGuard traffic){this.traffic=traffic;}
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        response.setHeader("X-Content-Type-Options","nosniff");response.setHeader("X-Frame-Options","DENY");response.setHeader("Referrer-Policy","no-referrer");
        response.setHeader("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        if(request.isSecure())response.setHeader("Strict-Transport-Security","max-age=31536000");
        String path=request.getRequestURI().toLowerCase(java.util.Locale.ROOT);
        if(path.contains(";")||path.contains("\\")||path.contains("//")||path.matches(".*%(2f|5c|00|0a|0d|25|2e).*")){
            response.setStatus(400);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");response.getWriter().write("{\"error\":\"BAD_REQUEST\",\"message\":\"请求路径不合法\"}");return;
        }
        if(java.util.Set.of("TRACE","TRACK","CONNECT").contains(request.getMethod())){response.setStatus(405);return;}
        if(isWriteMethod(request.getMethod())&&request.getContentLengthLong()!=0&&request.getContentType()!=null){
            String type=request.getContentType().toLowerCase(java.util.Locale.ROOT);
            if(!type.startsWith("application/json")&&!type.startsWith("multipart/form-data")){response.setStatus(415);response.setContentType("application/json");response.setCharacterEncoding("UTF-8");response.getWriter().write("{\"error\":\"UNSUPPORTED_MEDIA_TYPE\",\"message\":\"此接口仅接受JSON或文件上传\"}");return;}
        }
        if(!path.equals("/actuator/health")&&!traffic.allow("ip:"+request.getRemoteAddr(),600)){
            response.setStatus(429);response.setHeader("Retry-After","60");response.setHeader("Cache-Control","no-store");response.setContentType("application/json");response.setCharacterEncoding("UTF-8");response.getWriter().write("{\"error\":\"TOO_MANY_REQUESTS\",\"message\":\"请求过于频繁，请稍后再试\"}");return;
        }
        chain.doFilter(request,response);
    }
    private static boolean isWriteMethod(String method){return "POST".equals(method)||"PUT".equals(method);}
}
