package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * Query 改写 — 仅 intent / planner 开关（路由与规划域）。
 * 模型名 → ModelSceneResolver（rewrite.intent / rewrite.planner）；
 * system-prompt / timeline 文案 → Catalog {@code rewrite.intent|planner|timeline}。
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
    }

    @Data
    public static class Intent {
        private boolean enabled = true;
        private int maxChars = 8;
    }

    public Planner plannerOrDefault() {
        return planner != null ? planner : new Planner();
    }
}
