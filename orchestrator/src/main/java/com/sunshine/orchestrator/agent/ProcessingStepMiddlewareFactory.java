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
 * AS2 P1：ProcessingStepMiddleware 工厂。P2-1（E5）起 middleware 无状态（bridgeId 经
 * RuntimeContext 注入），全应用共享单实例，供 HarnessAgent 指纹缓存安全复用。
 */
@Component
@RequiredArgsConstructor
public class ProcessingStepMiddlewareFactory {

    private final ToolCatalogService toolCatalogService;
    private final AgentExecutionProperties executionProperties;
    private final TaskBoardTimelineSupport taskBoardTimelineSupport;
    private final SandboxTimelineLabelService sandboxTimelineLabels;
    private final CancellableToolRunRegistry cancellableToolRunRegistry;

    private volatile MiddlewareBase shared;

    /** 共享无状态实例（bridgeId per-call 注入，非构造参数） */
    public MiddlewareBase shared() {
        MiddlewareBase s = shared;
        if (s == null) {
            synchronized (this) {
                if (shared == null) {
                    shared = new ProcessingStepMiddleware(
                            toolCatalogService,
                            executionProperties,
                            taskBoardTimelineSupport,
                            sandboxTimelineLabels,
                            cancellableToolRunRegistry);
                }
                s = shared;
            }
        }
        return s;
    }
}
