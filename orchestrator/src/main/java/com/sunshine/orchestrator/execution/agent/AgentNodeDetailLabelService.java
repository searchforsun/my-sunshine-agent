package com.sunshine.orchestrator.execution.agent;

import com.sunshine.orchestrator.execution.WorkflowTimelineLabels;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Workflow agent 节点时间线摘要模板 */
@Service
public class AgentNodeDetailLabelService {

    @PostConstruct
    void init() {
        AgentNodeDetailSummarizer.bind(this);
    }

    public String afterWithTools(int toolCallCount) {
        return WorkflowTimelineLabels.apply(
                WorkflowTimelineLabels.AGENT_AFTER_WITH_TOOLS, "{toolCallCount}", String.valueOf(toolCallCount));
    }

    public String afterDone() {
        return WorkflowTimelineLabels.AGENT_AFTER_DONE;
    }

    public String skillLoadedLine(String skillLabel) {
        if (!StringUtils.hasText(skillLabel)) {
            return "";
        }
        return WorkflowTimelineLabels.apply(
                WorkflowTimelineLabels.AGENT_SKILL_LOADED_PREFIX, "{skillLabel}", skillLabel.strip());
    }
}
