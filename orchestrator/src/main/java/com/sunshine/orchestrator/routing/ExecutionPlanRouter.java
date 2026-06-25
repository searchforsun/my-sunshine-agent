package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.routing.policy.RoutingPolicyChain;
import com.sunshine.orchestrator.skill.SkillDiscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 路由入口 — 委托 RoutingPolicyChain（Skill → Structural → GoldenRule → LLM）+ Skill 自动发现 */
@Component
@RequiredArgsConstructor
public class ExecutionPlanRouter {

    private final RoutingPolicyChain policyChain;
    private final SkillDiscoveryService skillDiscoveryService;
    private final ForcedExecutionRouter forcedExecutionRouter;

    public Mono<ExecutionPlan> route(String userMessage) {
        return route(userMessage, null);
    }

    public Mono<ExecutionPlan> route(String userMessage, String traceMessageId) {
        return route(new RoutingContext(userMessage, traceMessageId));
    }

    public Mono<ExecutionPlan> route(RoutingContext ctx) {
        ExecutionPreference preference = ctx.preference() != null ? ctx.preference() : ExecutionPreference.AUTO;
        if (preference.isForced()) {
            forcedExecutionRouter.validatePreference(ctx.userMessage(), preference);
            return forcedExecutionRouter.resolve(ctx, preference, ctx.forcedWorkflowId())
                    .map(plan -> preference == ExecutionPreference.REACT
                            ? skillDiscoveryService.enrich(plan, ctx.userMessage())
                            : plan);
        }
        return policyChain.route(ctx)
                .map(plan -> skillDiscoveryService.enrich(plan, ctx.userMessage()));
    }
}
