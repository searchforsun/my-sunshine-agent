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
        return apply(template().getPending(), toolDisplayName);
    }

    public String awaiting() {
        String t = template().getAwaiting();
        return StringUtils.hasText(t) ? t : "等待用户确认执行写操作";
    }

    public String approved(String toolDisplayName) {
        return apply(template().getApproved(), toolDisplayName);
    }

    public String denied() {
        String t = template().getDenied();
        return StringUtils.hasText(t) ? t : "用户取消调用";
    }

    public String skippedAfter() {
        String t = template().getSkippedAfter();
        return StringUtils.hasText(t) ? t : "用户取消调用，已跳过";
    }

    private AgentPromptProperties.HitlTimeline template() {
        AgentPromptProperties.Timeline timeline = agentPromptProperties.timelineOrDefault();
        return timeline.getHitl() != null ? timeline.getHitl() : new AgentPromptProperties.HitlTimeline();
    }

    private static String apply(String template, String toolDisplayName) {
        if (!StringUtils.hasText(template)) {
            return "将调用工具 " + toolDisplayName;
        }
        return template.replace("{toolDisplayName}", toolDisplayName != null ? toolDisplayName : "");
    }
}
