package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.routing.ExecutionMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 按 ExecutionPlan.mode 分发至对应 Executor
 */
@Component
@RequiredArgsConstructor
public class ExecutionDispatcher {

    private final SimpleLlmExecutor simpleLlmExecutor;
    private final WorkflowExecutor workflowExecutor;
    private final ReactExecutor reactExecutor;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        ExecutionMode mode = ctx.plan() != null ? ctx.plan().mode() : ExecutionMode.REACT;
        return switch (mode) {
            case SIMPLE_LLM -> simpleLlmExecutor.execute(ctx);
            case WORKFLOW -> workflowExecutor.execute(ctx);
            case REACT -> reactExecutor.execute(ctx);
        };
    }
}
