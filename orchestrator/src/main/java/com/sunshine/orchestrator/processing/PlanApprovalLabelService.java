package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Plan 用户确认时间线文案 — SSOT：Nacos agent.timeline.plan-approval */
@Service
@RefreshScope
@RequiredArgsConstructor
public class PlanApprovalLabelService {

    private final AgentPromptProperties agentPromptProperties;

    @PostConstruct
    void init() {
        PlanApprovalLabels.bind(this);
    }

    public String awaiting() {
        return textOrDefault(template().getAwaiting(), "等待确认执行计划");
    }

    public String approved() {
        return textOrDefault(template().getApproved(), "已确认执行计划");
    }

    public String regenerating() {
        return textOrDefault(template().getRegenerating(), "正在根据修改意见重新规划…");
    }

    public String timedOut() {
        return textOrDefault(template().getTimedOut(), "确认超时，将改由自主智能体继续");
    }

    private AgentPromptProperties.PlanApprovalTimeline template() {
        AgentPromptProperties.Timeline timeline = agentPromptProperties.timelineOrDefault();
        return timeline.getPlanApproval() != null
                ? timeline.getPlanApproval()
                : new AgentPromptProperties.PlanApprovalTimeline();
    }

    private static String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }
}
