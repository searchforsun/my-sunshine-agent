package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.StructuralPlanMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/** L1：跨领域多步结构守卫 → plan-workflow（模式/关键词见 Nacos agent.routing.structural） */
@Component
@RequiredArgsConstructor
public class StructuralRoutingPolicy implements RoutingPolicy {

    private final StructuralPlanMatcher structuralPlanMatcher;

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Mono<Optional<ExecutionPlan>> tryRoute(RoutingContext ctx) {
        if (!structuralPlanMatcher.looksLikeMultiStepPlan(ctx.userMessage())) {
            return Mono.just(Optional.empty());
        }
        return Mono.just(Optional.of(new ExecutionPlan(
                ExecutionMode.PLAN_WORKFLOW, null, Map.of(), "structural:multi-step-plan")));
    }
}
