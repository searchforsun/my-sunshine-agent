package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 子 Agent 时间线文案 — Nacos agent.timeline.steps.subagent */
@Service
@RefreshScope
@RequiredArgsConstructor
public class SpawnSubagentLabelService {

    private final AgentPromptProperties agentPromptProperties;

    @PostConstruct
    void init() {
        SpawnSubagentLabels.bind(this);
    }

    public String label() {
        return TimelineLabelTemplates.textOrDefault(step().getLabel(), "子任务");
    }

    public String before() {
        return TimelineLabelTemplates.textOrDefault(step().getBefore(), "准备委派子任务");
    }

    public String active(String labelPlaceholder) {
        String tpl = TimelineLabelTemplates.textOrDefault(step().getActive(), "正在执行：{label}");
        String value = StringUtils.hasText(labelPlaceholder) ? labelPlaceholder.strip() : label();
        return tpl.replace("{label}", value);
    }

    public String after() {
        return TimelineLabelTemplates.textOrDefault(step().getAfter(), "子任务已完成");
    }

    public String afterFail() {
        return TimelineLabelTemplates.textOrDefault(step().getAfterFail(), "子任务失败");
    }

    public String afterCancel() {
        return TimelineLabelTemplates.textOrDefault(step().getAfterCancel(), "已取消");
    }

    private AgentPromptProperties.StepTimeline step() {
        var steps = agentPromptProperties.timelineOrDefault().getSteps();
        if (steps == null) {
            return new AgentPromptProperties.StepTimeline();
        }
        AgentPromptProperties.StepTimeline found = steps.get("subagent");
        return found != null ? found : new AgentPromptProperties.StepTimeline();
    }
}
