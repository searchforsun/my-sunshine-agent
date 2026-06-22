package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.config.AgentRewriteProperties;
import org.springframework.util.StringUtils;

/** Query 改写时间线展开区场景说明 — 配置见 Nacos agent.rewrite.timeline */
public final class RewriteTimelineLabels {

    private static volatile AgentRewriteProperties rewriteProperties;

    private RewriteTimelineLabels() {
    }

    public static void bind(AgentRewriteProperties properties) {
        rewriteProperties = properties;
    }

    public static String labelFor(String scenario) {
        if (rewriteProperties != null) {
            String label = rewriteProperties.timelineOrDefault().labelFor(scenario);
            if (StringUtils.hasText(label)) {
                return label.strip();
            }
        }
        return "";
    }
}
