package com.agentops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 桌面客户端（Electron，file:// 协议，Origin 为 null）与开发期 Vite dev server
 * 需要跨域访问本服务（含 SSE），这里统一放行本地来源。
 */
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer agentCorsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOriginPatterns("*")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .exposedHeaders("Content-Disposition", "X-Artifact-Name")
                        .maxAge(3600);
            }
        };
    }
}
