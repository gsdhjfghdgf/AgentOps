package com.agentops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 单 Agent 应用入口。
 *
 * 设计依据：《Agent 设计与实现文档》v1.2。
 * 运行链路：RunApplicationService(创建/命令) → RunScheduler(领取) → AgentEngine(tick)
 *          → RunCoordinator(短事务边界) → ModelPort / ToolGateway / ResultValidator。
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class AgentServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentServerApplication.class, args);
    }
}
