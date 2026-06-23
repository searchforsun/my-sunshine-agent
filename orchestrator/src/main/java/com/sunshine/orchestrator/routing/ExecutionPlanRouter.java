package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.routing.policy.RoutingPolicyChain;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 路由入口 — 委托 RoutingPolicyChain（Skill → Structural → GoldenRule → LLM） */
@Component
@RequiredArgsConstructor
public class ExecutionPlanRouter {

    private final RoutingPolicyChain policyChain;

    public Mono<ExecutionPlan> route(String userMessage) {
        return route(userMessage, null);
    }

    public Mono<ExecutionPlan> route(String userMessage, String traceMessageId) {
        return policyChain.route(new RoutingContext(userMessage, traceMessageId));
    }
}
