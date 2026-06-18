package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 执行模式配置 — react 工具白名单等（Nacos agent.execution）
 */
@Data
@RefreshScope
@ConfigurationProperties(prefix = "agent.execution")
public class AgentExecutionProperties {

    private String defaultMode = "react";
    private React react = new React();

    @Data
    public static class React {
        private List<String> tools = new ArrayList<>(List.of(
                "search_knowledge",
                "list_finance_messages",
                "get_finance_message_detail",
                "summarize_finance_by_status",
                "list_oa_tasks"));
        private int maxIters = 5;
    }
}
