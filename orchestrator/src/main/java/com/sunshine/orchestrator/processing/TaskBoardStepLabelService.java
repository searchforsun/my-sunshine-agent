package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** TaskBoard {@code tasks} 步文案 — Nacos agent.timeline.steps.tasks */
@Service
@RefreshScope
@RequiredArgsConstructor
public class TaskBoardStepLabelService {

    private final AgentPromptProperties agentPromptProperties;

    @PostConstruct
    void init() {
        TaskBoardStepLabels.bind(this);
    }

    public String label() {
        AgentPromptProperties.StepTimeline step = tasksStep();
        return TimelineLabelTemplates.textOrDefault(step != null ? step.getLabel() : null, "任务清单");
    }

    public String before() {
        AgentPromptProperties.StepTimeline step = tasksStep();
        return TimelineLabelTemplates.textOrDefault(step != null ? step.getBefore() : null, "规划任务步骤");
    }

    public String active(String activeTask) {
        AgentPromptProperties.StepTimeline step = tasksStep();
        String template = TimelineLabelTemplates.textOrDefault(
                step != null ? step.getActive() : null, "正在执行：{activeTask}");
        return TimelineLabelTemplates.applyTemplate(template, TimelineLabelTemplates.taskVars(activeTask, null));
    }

    public String after() {
        AgentPromptProperties.StepTimeline step = tasksStep();
        return TimelineLabelTemplates.textOrDefault(step != null ? step.getAfter() : null, "任务清单已更新");
    }

    public String allDone() {
        AgentPromptProperties.StepTimeline step = tasksStep();
        if (step != null && StringUtils.hasText(step.getAllDone())) {
            return step.getAllDone().strip();
        }
        return "全部任务已完成";
    }

    private AgentPromptProperties.StepTimeline tasksStep() {
        var steps = agentPromptProperties.timelineOrDefault().getSteps();
        if (steps == null) {
            return null;
        }
        return steps.get(TimelineStepId.TASKS.id());
    }
}
