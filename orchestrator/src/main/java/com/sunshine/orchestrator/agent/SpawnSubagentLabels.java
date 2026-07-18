package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 子 Agent 时间线文案 — Nacos agent.timeline.steps.subagent */
@Component
@RefreshScope
@RequiredArgsConstructor
public class SpawnSubagentLabels {

    private static volatile SpawnSubagentLabels instance;

    private final AgentPromptProperties agentPromptProperties;

    @PostConstruct
    void init() {
        instance = this;
    }

    /** 单测可 bind 假实例或清掉 */
    public static void bind(SpawnSubagentLabels labels) {
        instance = labels;
    }

    public static String label() {
        return text(step().getLabel(), "子任务");
    }

    public static String before() {
        return text(step().getBefore(), "准备委派子任务");
    }

    public static String active(String labelPlaceholder) {
        String tpl = StringUtils.hasText(step().getActive())
                ? step().getActive().strip()
                : "正在执行：{label}";
        String value = StringUtils.hasText(labelPlaceholder) ? labelPlaceholder.strip() : label();
        return tpl.replace("{label}", value);
    }

    public static String after() {
        return text(step().getAfter(), "子任务已完成");
    }

    public static String afterFail() {
        return text(step().getAfterFail(), "子任务失败");
    }

    public static String afterCancel() {
        return text(step().getAfterCancel(), "子任务已取消");
    }

    private static AgentPromptProperties.StepTimeline step() {
        if (instance == null || instance.agentPromptProperties == null) {
            return new AgentPromptProperties.StepTimeline();
        }
        var steps = instance.agentPromptProperties.timelineOrDefault().getSteps();
        if (steps == null) {
            return new AgentPromptProperties.StepTimeline();
        }
        AgentPromptProperties.StepTimeline found = steps.get("subagent");
        return found != null ? found : new AgentPromptProperties.StepTimeline();
    }

    private static String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }
}
