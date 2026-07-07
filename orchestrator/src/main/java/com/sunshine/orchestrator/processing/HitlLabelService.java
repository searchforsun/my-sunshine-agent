package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 写工具 HITL 时间线文案 — SSOT：Nacos agent.timeline.hitl */
@Service
@RefreshScope
@RequiredArgsConstructor
public class HitlLabelService {

    private final AgentPromptProperties agentPromptProperties;

    @PostConstruct
    void init() {
        HitlLabels.bind(this);
    }

    public String pending(String toolDisplayName) {
        return replaceTool(template().getPending(), toolDisplayName, defaults().getPending());
    }

    public String awaiting() {
        return textOrDefault(template().getAwaiting(), defaults().getAwaiting());
    }

    public String approved(String toolDisplayName) {
        return replaceTool(template().getApproved(), toolDisplayName, defaults().getApproved());
    }

    public String denied() {
        return textOrDefault(template().getDenied(), defaults().getDenied());
    }

    public String skippedAfter() {
        return textOrDefault(template().getSkippedAfter(), defaults().getSkippedAfter());
    }

    private AgentPromptProperties.HitlTimeline template() {
        AgentPromptProperties.Timeline timeline = agentPromptProperties.timelineOrDefault();
        return timeline.getHitl() != null ? timeline.getHitl() : defaults();
    }

    private static AgentPromptProperties.HitlTimeline defaults() {
        return new AgentPromptProperties.HitlTimeline();
    }

    private static String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }

    private static String replaceTool(String template, String toolDisplayName, String fallbackTemplate) {
        String resolved = textOrDefault(template, fallbackTemplate);
        return resolved.replace("{toolDisplayName}", toolDisplayName != null ? toolDisplayName : "");
    }
}
