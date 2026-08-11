package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.prompt.TimelinePromptCatalog;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** request_decision 时间线文案 — Catalog timeline.steps.decision */
@Service
@RefreshScope
@RequiredArgsConstructor
public class DecisionLabelService {

    private final TimelinePromptCatalog timelinePromptCatalog;

    @PostConstruct
    void init() {
        DecisionLabels.bind(this);
    }

    public String label() {
        return TimelineLabelTemplates.textOrDefault(step().getLabel(), "用户决策");
    }

    public String before() {
        return TimelineLabelTemplates.textOrDefault(step().getBefore(), "正在等待用户决策");
    }

    public String active(String question) {
        String tpl = TimelineLabelTemplates.textOrDefault(step().getActive(), "等待决策：{question}");
        String value = StringUtils.hasText(question) ? question : "";
        return tpl.replace("{question}", value);
    }

    public String after(String choice) {
        String tpl = TimelineLabelTemplates.textOrDefault(step().getAfter(), "用户已选择：{choice}");
        String value = StringUtils.hasText(choice) ? choice.strip() : "";
        return tpl.replace("{choice}", value);
    }

    public String afterFail() {
        return TimelineLabelTemplates.textOrDefault(step().getAfterFail(), "决策失败");
    }

    /** Catalog 无独立 after-timeout 字段；缺省固定文案 */
    public String afterTimeout() {
        return "决策已超时";
    }

    public String afterCancel() {
        return TimelineLabelTemplates.textOrDefault(step().getAfterCancel(), "已取消");
    }

    private AgentPromptProperties.StepTimeline step() {
        var steps = timelinePromptCatalog.steps();
        if (steps == null) {
            return new AgentPromptProperties.StepTimeline();
        }
        AgentPromptProperties.StepTimeline found = steps.get("decision");
        return found != null ? found : new AgentPromptProperties.StepTimeline();
    }
}
