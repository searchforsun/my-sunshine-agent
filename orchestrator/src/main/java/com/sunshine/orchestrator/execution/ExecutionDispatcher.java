package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.plan.harness.PlannerHarnessExecutor;
import com.sunshine.orchestrator.routing.ExecutionMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 按 ExecutionPlan.mode 分发至对应 Executor。
 * PLAN_WORKFLOW：{@code harness.enabled=true} 时走 PlannerHarnessExecutor，否则仍走 PlanWorkflowExecutor。
 */
@Component
@RequiredArgsConstructor
public class ExecutionDispatcher {

    private final WorkflowExecutor workflowExecutor;
    private final ReactExecutor reactExecutor;
    private final PlanWorkflowExecutor planWorkflowExecutor;
    private final PlannerHarnessExecutor plannerHarnessExecutor;
    private final AgentExecutionProperties executionProperties;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        ExecutionMode mode = ctx.plan() != null ? ctx.plan().mode() : ExecutionMode.REACT;
        return switch (mode) {
            case WORKFLOW -> workflowExecutor.execute(ctx);
            case REACT -> reactExecutor.execute(ctx);
            case PLAN_WORKFLOW -> harnessEnabled()
                    ? plannerHarnessExecutor.execute(ctx)
                    : planWorkflowExecutor.execute(ctx);
        };
    }

    private boolean harnessEnabled() {
        return executionProperties.getHarness() != null && executionProperties.getHarness().isEnabled();
    }
}
