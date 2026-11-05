package com.agentops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.concurrent.Executor;

/** 通用装配：HTTP 客户端、Redis 模板、时钟与执行线程池。 */
@Configuration
public class AppConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** OpenAI 兼容 HTTP 客户端（超时来自配置）。 */
    @Bean
    @org.springframework.context.annotation.Primary
    RestClient restClient(AgentProperties props) {
        return modelClient(props, props.model().timeoutSeconds());
    }

    @Bean
    RestClient streamingRestClient(AgentProperties props) {
        // The stream monitor handles first-byte/idle waits. A 60s transport deadline
        // would otherwise still cut off an actively generating response.
        return modelClient(props, props.model().streamTimeouts().maxDurationSeconds());
    }

    private RestClient modelClient(AgentProperties props, int timeoutSeconds) {
        var http=java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(10)).build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(java.time.Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder()
                .baseUrl(props.model().baseUrl() == null ? "http://localhost" : props.model().baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Bean
    StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    /** Agent 执行器：阻塞 JDBC 与模型调用都放在受控线程池，不放事件循环（文档 17.3）。 */
    @Bean(name = "agentExecutor")
    Executor agentExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("agent-worker-");
        executor.initialize();
        return executor;
    }
}
