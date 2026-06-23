package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.routing.RuleBasedRouter;
import com.sunshine.orchestrator.routing.StructuralPlanMatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;

/** L2：Nacos 黄金规则快路径（L1 漏判时跳过多步 query，避免 finance-list 误吞组合问句） */
@Component
@RequiredArgsConstructor
public class GoldenRuleRoutingPolicy implements RoutingPolicy {

    private final RuleBasedRouter ruleBasedRouter;
    private final StructuralPlanMatcher structuralPlanMatcher;

    @Override
    public int order() {
        return 20;
    }

    @Override
    public Mono<Optional<ExecutionPlan>> tryRoute(RoutingContext ctx) {
        if (structuralPlanMatcher.looksLikeMultiStepPlan(ctx.userMessage())) {
            return Mono.just(Optional.empty());
        }
        return Mono.just(ruleBasedRouter.match(ctx.userMessage()));
    }
}
