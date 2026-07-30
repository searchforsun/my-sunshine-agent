package com.sunshine.orchestrator.catalog;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RefreshScope
@RequiredArgsConstructor
public class AgentStepLabels {
    private static AgentStepLabels instance;
    private final TimelinePromptCatalog timelinePromptCatalog;

    @PostConstruct
    void init() {
        instance = this;
    }

    public static String conveneLabel() {
        return text(step("expert-convene").getLabel(), "多专家协作");
    }

    public static String conveneBefore() {
        return text(step("expert-convene").getBefore(), "正在匹配协作专家");
    }

    public static String conveneActive() {
        return text(step("expert-convene").getActive(), "正在召集专家");
    }

    public static String conveneAfter(String expertNames) {
        String tpl = text(step("expert-convene").getAfter(), "已召集：{expertNames}");
        return tpl.replace("{expertNames}", expertNames != null ? expertNames : "");
    }

    public static String expertLabel(String displayName) {
        String tpl = text(step("expert").getLabel(), "{displayName}");
        return tpl.replace("{displayName}", displayName != null ? displayName : "专家");
    }

    public static String expertBefore(String displayName) {
        String tpl = text(step("expert").getBefore(), "准备听取{displayName}意见");
        return tpl.replace("{displayName}", displayName != null ? displayName : "专家");
    }

    public static String expertActive(String displayName) {
        String tpl = text(step("expert").getActive(), "{displayName}正在分析");
        return tpl.replace("{displayName}", displayName != null ? displayName : "专家");
    }

    public static String expertActiveResponding(String displayName) {
        AgentPromptProperties.StepTimeline step = step("expert");
        String tpl = StringUtils.hasText(step.getActiveResponding())
                ? step.getActiveResponding()
                : "{displayName}正在回应其他专家观点";
        return tpl.replace("{displayName}", displayName != null ? displayName : "专家");
    }

    public static String expertAfter(String displayName) {
        String tpl = text(step("expert").getAfter(), "{displayName}已完成发言");
        return tpl.replace("{displayName}", displayName != null ? displayName : "专家");
    }

    private static AgentPromptProperties.StepTimeline step(String key) {
        if (instance == null) {
            return new AgentPromptProperties.StepTimeline();
        }
        var steps = instance.timelinePromptCatalog.steps();
        if (steps == null) {
            return new AgentPromptProperties.StepTimeline();
        }
        AgentPromptProperties.StepTimeline found = steps.get(key);
        return found != null ? found : new AgentPromptProperties.StepTimeline();
    }

    private static String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }
}
