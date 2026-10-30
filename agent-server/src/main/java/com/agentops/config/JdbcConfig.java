package com.agentops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 用支持 {@link java.time.Instant} 参数的 JdbcTemplate 替换 Boot 自动配置的默认实例
 * （Boot 的 JdbcTemplateAutoConfiguration 有 @ConditionalOnMissingBean，本 Bean 优先生效）。
 */
@Configuration
public class JdbcConfig {

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new InstantAwareJdbcTemplate(dataSource);
    }
}
