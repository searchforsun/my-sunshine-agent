package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * Query 改写 — 仅 intent / planner 开关与模型（路由与规划域）。
 * system-prompt / timeline 文案 → Catalog {@code rewrite.intent|planner|timeline}。
 * RAG 检索改写 SSOT：sunshine-rag.yaml {@code rag.rewrite.*}（ADR-002）。
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.rewrite")
public class AgentRewriteProperties {
    private Intent intent = new Intent();
    private Planner planner = new Planner();

    @Data
    public static class Planner {
        private boolean enabled = true;
        private String model = "deepseek-v4-flash";
    }

    @Data
    public static class Intent {
        private boolean enabled = true;
        private String model = "deepseek-v4-flash";
        private int maxChars = 8;
    }

    public Planner plannerOrDefault() {
        return planner != null ? planner : new Planner();
    }
}
