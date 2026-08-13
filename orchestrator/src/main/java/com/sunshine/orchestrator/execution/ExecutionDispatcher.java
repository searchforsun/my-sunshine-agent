package com.sunshine.orchestrator.execution;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.plan.harness.PlannerHarnessExecutor;
import com.sunshine.orchestrator.routing.ExecutionMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * ResourceDispatcher（类名过渡保留 ExecutionDispatcher）：按用户钉死的
 * {@link ExecutionMode} 分发 — FAST→ReAct、PRO→PlannerHarness、WORKFLOW→静态 Workflow。
 * <p>PRO 在 {@code harness.enabled=false} 时显式失败，禁止回落旧动态规划、禁止静默改 FAST。
 */
@Component
@RequiredArgsConstructor
public class ExecutionDispatcher {

    private final WorkflowExecutor workflowExecutor;
    private final ReactExecutor reactExecutor;
    private final PlannerHarnessExecutor plannerHarnessExecutor;
    private final AgentExecutionProperties executionProperties;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        ExecutionMode mode = ctx.plan() != null ? ctx.plan().mode() : ExecutionMode.FAST;
        return switch (mode) {
            case FAST -> reactExecutor.execute(ctx);
            case PRO -> {
                if (!harnessEnabled()) {
                    yield Flux.error(new BizException(OrchestratorErrorCode.HARNESS_DISABLED));
                }
                yield plannerHarnessExecutor.execute(ctx);
            }
            case WORKFLOW -> workflowExecutor.execute(ctx);
        };
    }

    private boolean harnessEnabled() {
        return executionProperties.getHarness() != null && executionProperties.getHarness().isEnabled();
    }
}
