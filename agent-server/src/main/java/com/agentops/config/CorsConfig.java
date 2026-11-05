package com.agentops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;

/**
 * 桌面客户端（Electron，file:// 协议，Origin 为 null）与开发期 Vite dev server
 * 需要跨域访问本服务（含 SSE），这里统一放行本地来源。
 */
@Configuration
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> agentCorsConfigurer(@Value("${agent.web.allowed-origins:null,http://localhost:5173,http://127.0.0.1:5173}")List<String> origins) {
        var config=new CorsConfiguration();config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization","Content-Type","X-Tenant-Id","X-User-Id","Last-Event-ID"));
        config.setExposedHeaders(List.of("Content-Disposition","X-Artifact-Name","X-Request-Id"));config.setMaxAge(3600L);
        var source=new UrlBasedCorsConfigurationSource();source.registerCorsConfiguration("/api/**",config);
        var filter=new FilterRegistrationBean<>(new CorsFilter(source));filter.setOrder(Ordered.HIGHEST_PRECEDENCE);return filter;
    }
}
