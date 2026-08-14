package com.sunshine.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

/**
 * Query 改写 — 仅 intent（路由域）。
 * 模型名 → ModelSceneResolver（rewrite.intent）；
 * system-prompt / timeline 文案 → Catalog {@code rewrite.intent|timeline}。
 */
@Data
@Component
@RefreshScope
@ConfigurationProperties(prefix = "agent.rewrite")
public class AgentRewriteProperties {
    private Intent intent = new Intent();

    @Data
    public static class Intent {
        private boolean enabled = true;
        private int maxChars = 8;
    }
}
