package com.agentops.tool;

import com.agentops.domain.ToolTypes.ToolSpec;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工具引导：启动时把业务工具注册进 ToolRegistry（文档 11.1）。
 * 副作用类别与批准要求由这里声明，不由模型填写。
 */
@Component
public class ToolBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ToolBootstrap.class);

    private final ToolRegistry registry;
    private final SalesTools sales;
    private final ReportTools report;
    private final MessagingTools messaging;
    private final KnowledgeTools knowledge;

    public ToolBootstrap(ToolRegistry registry, SalesTools sales, ReportTools report,
                         MessagingTools messaging, KnowledgeTools knowledge) {
        this.registry = registry;
        this.sales = sales;
        this.report = report;
        this.messaging = messaging;
        this.knowledge = knowledge;
    }

    @PostConstruct
    void registerAll() {
        register(SalesTools.spec(), sales);
        register(ReportTools.spec(), report);
        register(MessagingTools.spec(), messaging);
        register(KnowledgeTools.spec(), knowledge);
        log.info("工具注册完成：{}", registry.names());
    }

    private void register(ToolSpec spec, ToolRegistry.ToolHandler handler) {
        registry.register(spec, handler);
    }
}
