package com.sunshine.orchestrator.routing.policy;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.orchestrator.exception.OrchestratorErrorCode;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.workflow.WorkflowBindingOutcome;
import com.sunshine.orchestrator.workflow.WorkflowBindingParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/** L0：# workflow 硬绑定（优先于 $ / @） */
@Component
@RequiredArgsConstructor
public class WorkflowBindingRoutingPolicy implements RoutingPolicy {
    private final WorkflowBindingParser workflowBindingParser;

    @Override
    public int order() {
        return -20;
    }

    @Override
    public Mono<Optional<ExecutionPlan>> tryRoute(RoutingContext ctx) {
        WorkflowBindingOutcome binding = workflowBindingParser.parse(ctx.userMessage());
        if (binding.unknown()) {
            return Mono.error(new BizException(OrchestratorErrorCode.WORKFLOW_NOT_FOUND));
        }
        if (!binding.bound()) {
            return Mono.just(Optional.empty());
        }
        return Mono.just(Optional.of(new ExecutionPlan(
                ExecutionMode.WORKFLOW,
                binding.workflowId(),
                Map.of("effectiveQuery", binding.effectiveQuery()),
                "workflow:#mention")));
    }
}
