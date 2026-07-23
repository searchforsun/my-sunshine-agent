package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.sandbox.CancellableToolRunRegistry;
import com.sunshine.orchestrator.sandbox.SandboxTimelineLabelService;
import com.sunshine.orchestrator.taskboard.TaskBoardTimelineSupport;
import io.agentscope.core.middleware.MiddlewareBase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AS2 P1：为每个 ReAct 请求按 bridgeId 构造 {@link ProcessingStepMiddleware}。
 * 替代 legacy ProcessingStepHookFactory。
 */
@Component
@RequiredArgsConstructor
public class ProcessingStepMiddlewareFactory {

    private final ToolCatalogService toolCatalogService;
    private final AgentExecutionProperties executionProperties;
    private final TaskBoardTimelineSupport taskBoardTimelineSupport;
    private final SandboxTimelineLabelService sandboxTimelineLabels;
    private final CancellableToolRunRegistry cancellableToolRunRegistry;

    public MiddlewareBase forBridge(String bridgeId) {
        return new ProcessingStepMiddleware(
                bridgeId,
                toolCatalogService,
                executionProperties,
                taskBoardTimelineSupport,
                sandboxTimelineLabels,
                cancellableToolRunRegistry);
    }
}
