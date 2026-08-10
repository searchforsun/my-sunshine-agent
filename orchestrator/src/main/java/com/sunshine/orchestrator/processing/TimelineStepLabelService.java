package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 标准步骤 label / before / active / after — Nacos agent.timeline.steps（intent / plan / rag / generate / skill 等）
 */
@Service
@RefreshScope
@RequiredArgsConstructor
public class TimelineStepLabelService {

    private final TimelinePromptCatalog timelinePromptCatalog;

    @PostConstruct
    void init() {
        TimelineLabels.bind(this);
        TimelineStepLabels.bind(this);
    }

    public String stepLabel(String stepId) {
        if (TimelineStepId.INTENT.matches(stepId)) {
            return TimelineLabelTemplates.textOrDefault(
                    timelinePromptCatalog.intent().getLabel(), "识别意图");
        }
        AgentPromptProperties.StepTimeline step = stepTemplate(stepId);
        if (step != null && StringUtils.hasText(step.getLabel())) {
            return step.getLabel().strip();
        }
        return null;
    }

    public String stepBefore(String stepId, String clippedQuery) {
        if (TimelineStepId.INTENT.matches(stepId)) {
            return TimelineLabelTemplates.applyTemplate(
                    timelinePromptCatalog.intent().getBefore(),
                    TimelineLabelTemplates.vars(clippedQuery, null, null, null));
        }
        AgentPromptProperties.StepTimeline step = stepTemplate(stepId);
        if (step != null && StringUtils.hasText(step.getBefore())) {
            return TimelineLabelTemplates.applyTemplate(step.getBefore(),
                    TimelineLabelTemplates.vars(clippedQuery, null, null, null));
        }
        return StepLabels.beforeFor(stepId);
    }

    public String stepActive(String stepId, String clippedQuery) {
        if (TimelineStepId.INTENT.matches(stepId)) {
            return TimelineLabelTemplates.applyTemplate(
                    timelinePromptCatalog.intent().getActive(),
                    TimelineLabelTemplates.vars(clippedQuery, null, null, null));
        }
        AgentPromptProperties.StepTimeline step = stepTemplate(stepId);
        if (step != null && StringUtils.hasText(step.getActive())) {
            return TimelineLabelTemplates.applyTemplate(step.getActive(),
                    TimelineLabelTemplates.vars(clippedQuery, null, null, null));
        }
        return StepLabels.activeFor(stepId);
    }

    /** plan / generate / skill 等步骤 after 模板 */
    public String stepAfter(String stepId, String clippedQuery, String detail) {
        if (TimelineStepId.PLAN.matches(stepId)) {
            if (StringUtils.hasText(detail)) {
                return detail.strip();
            }
            AgentPromptProperties.StepTimeline step = stepTemplate(TimelineStepId.PLAN.id());
            return TimelineLabelTemplates.textOrDefault(step != null ? step.getAfter() : null, "执行计划已生成");
        }
        if (TimelineStepId.GENERATE.matches(stepId)) {
            AgentPromptProperties.StepTimeline step = stepTemplate(TimelineStepId.GENERATE.id());
            String template = TimelineLabelTemplates.textOrDefault(
                    step != null ? step.getAfter() : null, "已完成回复");
            return TimelineLabelTemplates.applyTemplate(template,
                    TimelineLabelTemplates.vars(clippedQuery, null, null, null));
        }
        if (TimelineStepId.SKILL.matches(stepId)) {
            if (StringUtils.hasText(detail)) {
                return detail.strip();
            }
            AgentPromptProperties.StepTimeline step = stepTemplate(TimelineStepId.SKILL.id());
            return TimelineLabelTemplates.textOrDefault(step != null ? step.getAfterFallback() : null, "Skill 已加载");
        }
        return null;
    }

    private AgentPromptProperties.StepTimeline stepTemplate(String stepId) {
        var steps = timelinePromptCatalog.steps();
        if (steps == null || !StringUtils.hasText(stepId)) {
            return null;
        }
        return steps.get(stepId);
    }
}
