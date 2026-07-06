package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentPromptProperties;
import com.sunshine.orchestrator.config.WorkflowProperties;
import com.sunshine.orchestrator.execution.WorkflowNodeLabelService;
import org.mockito.Mockito;

import java.util.LinkedHashMap;

/** 单测绑定 Nacos 默认时间线模板，替代已删除的 IntentLabels/TimelineLabels fallback */
public final class TimelineLabelTestSupport {

    private TimelineLabelTestSupport() {
    }

    public static IntentLabelService bindDefaults() {
        AgentPromptProperties agentProps = new AgentPromptProperties();
        WorkflowProperties workflowProps = new WorkflowProperties();
        workflowProps.setDefinitions(new LinkedHashMap<>());
        IntentLabelService labelService = new IntentLabelService(
                agentProps,
                workflowProps,
                new WorkflowNodeLabelService(workflowProps, Mockito.mock(ToolCatalogService.class)));
        IntentLabels.bind(labelService);
        TimelineLabels.bind(labelService);
        return labelService;
    }

    public static void unbind() {
        IntentLabels.bind(null);
        TimelineLabels.bind(null);
    }
}
