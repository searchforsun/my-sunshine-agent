package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** await_tool_run / 后台 exec 时间线文案 — SSOT：Catalog timeline.steps.await-tool */
@Service
@RefreshScope
@RequiredArgsConstructor
public class AwaitToolRunLabelService {

    private static final String STEP_KEY = "await-tool";

    private final TimelinePromptCatalog timelinePromptCatalog;

    @PostConstruct
    void init() {
        AwaitToolRunLabels.bind(this);
    }

    public String label() {
        return TimelineLabelTemplates.textOrDefault(step().getLabel(), "等待结果");
    }

    public String before() {
        return TimelineLabelTemplates.textOrDefault(step().getBefore(), "准备等待后台任务");
    }

    public String active() {
        return TimelineLabelTemplates.textOrDefault(step().getActive(), "正在等待后台任务");
    }

    public String after() {
        return TimelineLabelTemplates.textOrDefault(step().getAfter(), "等待完成");
    }

    public String backgroundExecLabel() {
        String fromCatalog = step().getLabelFollowUp();
        return TimelineLabelTemplates.textOrDefault(fromCatalog, "后台执行");
    }

    private AgentPromptProperties.StepTimeline step() {
        AgentPromptProperties.StepTimeline found = timelinePromptCatalog.step(STEP_KEY);
        return found != null ? found : new AgentPromptProperties.StepTimeline();
    }
}
