package com.sunshine.orchestrator.config;

import com.sunshine.orchestrator.rewrite.QueryRewriteScenario;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * Query 改写 — 仅 intent / planner（路由与规划域）。
 * RAG 检索改写 SSOT：sunshine-rag.yaml {@code rag.rewrite.*}（ADR-002）。
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.rewrite")
public class AgentRewriteProperties {
    private Intent intent = new Intent();
    private Planner planner = new Planner();
    /** 时间线展开区场景说明 — 仅 intent / planner；RAG 场景由 rag-service trace 透传 */
    private Timeline timeline = new Timeline();

    @Data
    public static class Planner {
        private boolean enabled = true;
        private String model = "deepseek-v4-flash";
        private String systemPrompt = "";
    }

    @Data
    public static class Intent {
        private boolean enabled = true;
        private String model = "deepseek-v4-flash";
        private int maxChars = 8;
        private String systemPrompt = "";
    }

    @Data
    public static class Timeline {
        private String intent = "";
        private String planner = "";

        public String labelFor(String scenario) {
            if (scenario == null) {
                return "";
            }
            return switch (scenario) {
                case String s when QueryRewriteScenario.INTENT.matches(s) -> intent;
                case String s when QueryRewriteScenario.PLANNER.matches(s) -> planner;
                default -> "";
            };
        }
    }

    public Planner plannerOrDefault() {
        return planner != null ? planner : new Planner();
    }

    public Timeline timelineOrDefault() {
        return timeline != null ? timeline : new Timeline();
    }
}
