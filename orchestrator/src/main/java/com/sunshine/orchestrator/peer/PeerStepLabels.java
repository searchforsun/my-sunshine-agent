package com.sunshine.orchestrator.peer;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** peer-collab 步文案 — Nacos agent.timeline.steps.peer-collab */
@Component
@RefreshScope
@RequiredArgsConstructor
public class PeerStepLabels {

    private static PeerStepLabels instance;

    private final AgentPromptProperties agentPromptProperties;

    @PostConstruct
    void init() {
        instance = this;
    }

    static String label() {
        return text(step().getLabel(), "多专家协作");
    }

    static String before() {
        return text(step().getBefore(), "准备多专家协作");
    }

    static String active(String templateName) {
        String tpl = StringUtils.hasText(step().getActive())
                ? step().getActive()
                : "正在执行：{displayName}";
        return tpl.replace("{displayName}", templateName != null ? templateName : "协作模板");
    }

    static String after(int roleCount, int roundCount) {
        String tpl = StringUtils.hasText(step().getAfter())
                ? step().getAfter()
                : "已完成 {roleCount} 位专家、{roundCount} 轮交叉验证";
        return tpl.replace("{roleCount}", String.valueOf(roleCount))
                .replace("{roundCount}", String.valueOf(roundCount));
    }

    private static AgentPromptProperties.StepTimeline step() {
        if (instance == null) {
            return new AgentPromptProperties.StepTimeline();
        }
        var steps = instance.agentPromptProperties.timelineOrDefault().getSteps();
        if (steps == null) {
            return new AgentPromptProperties.StepTimeline();
        }
        AgentPromptProperties.StepTimeline found = steps.get("peer-collab");
        return found != null ? found : new AgentPromptProperties.StepTimeline();
    }

    private static String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }
}
