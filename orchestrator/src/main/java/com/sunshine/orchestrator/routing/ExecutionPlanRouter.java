package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.rewrite.QueryRewriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 规则路由优先；未命中时可选 intent 改写，再委托 LLM IntentRouter */
@Component
@RequiredArgsConstructor
public class ExecutionPlanRouter {

    private final RuleBasedRouter ruleBasedRouter;
    private final IntentRouter intentRouter;
    private final QueryRewriteService queryRewriteService;

    public Mono<ExecutionPlan> route(String userMessage) {
        return route(userMessage, null);
    }

    public Mono<ExecutionPlan> route(String userMessage, String traceMessageId) {
        return ruleBasedRouter.match(userMessage)
                .map(Mono::just)
                .orElseGet(() -> classifyWithOptionalIntentRewrite(userMessage, traceMessageId));
    }

    private Mono<ExecutionPlan> classifyWithOptionalIntentRewrite(String userMessage, String traceMessageId) {
        if (!queryRewriteService.shouldRewriteIntent(userMessage)) {
            return intentRouter.classifyPlan(userMessage);
        }
        return Mono.fromCallable(() -> queryRewriteService.rewriteForIntent(userMessage, traceMessageId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(outcome -> {
                    String query = StringUtils.hasText(outcome.effectiveQuery()) ? outcome.effectiveQuery() : userMessage;
                    return intentRouter.classifyPlan(query);
                });
    }
}
