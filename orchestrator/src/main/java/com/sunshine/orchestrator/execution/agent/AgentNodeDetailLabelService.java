package com.sunshine.orchestrator.execution.agent;

import com.sunshine.orchestrator.config.AgentPromptProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Workflow agent 节点时间线摘要模板 — SSOT：Nacos agent.timeline.workflow-agent */
@Service
@RefreshScope
@RequiredArgsConstructor
public class AgentNodeDetailLabelService {

    private final AgentPromptProperties agentPromptProperties;

    @PostConstruct
    void init() {
        AgentNodeDetailSummarizer.bind(this);
    }

    public String afterWithTools(int toolCallCount) {
        return applyTemplate(timeline().getAfterWithTools(), "{toolCallCount}", String.valueOf(toolCallCount));
    }

    public String afterDone() {
        String value = timeline().getAfterDone();
        return StringUtils.hasText(value) ? value.strip() : "智能体分析完成";
    }

    public String skillLoadedLine(String skillLabel) {
        if (!StringUtils.hasText(skillLabel)) {
            return "";
        }
        return applyTemplate(timeline().getSkillLoadedPrefix(), "{skillLabel}", skillLabel.strip());
    }

    private AgentPromptProperties.WorkflowAgentTimeline timeline() {
        AgentPromptProperties.WorkflowAgentTimeline cfg = agentPromptProperties.timelineOrDefault().getWorkflowAgent();
        return cfg != null ? cfg : new AgentPromptProperties.WorkflowAgentTimeline();
    }

    private static String applyTemplate(String template, String placeholder, String value) {
        if (!StringUtils.hasText(template)) {
            return "";
        }
        return template.strip().replace(placeholder, value != null ? value : "");
    }
}
