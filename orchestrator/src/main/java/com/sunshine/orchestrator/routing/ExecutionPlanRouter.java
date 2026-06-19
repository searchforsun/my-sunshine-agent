package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.agent.IntentRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 规则路由优先，未命中再委托 LLM IntentRouter */
@Component
@RequiredArgsConstructor
public class ExecutionPlanRouter {

    private final RuleBasedRouter ruleBasedRouter;
    private final IntentRouter intentRouter;

    public Mono<ExecutionPlan> route(String userMessage) {
        return ruleBasedRouter.match(userMessage)
                .map(Mono::just)
                .orElseGet(() -> intentRouter.classifyPlan(userMessage));
    }
}
