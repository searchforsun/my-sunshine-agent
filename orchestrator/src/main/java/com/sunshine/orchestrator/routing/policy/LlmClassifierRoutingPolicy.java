package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.rewrite.QueryRewriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Optional;

/** L3：Intent 改写 + LLM 意图分类（语义路由兜底） */
@Component
@RequiredArgsConstructor
public class LlmClassifierRoutingPolicy implements RoutingPolicy {

    private final IntentRouter intentRouter;
    private final QueryRewriteService queryRewriteService;

    @Override
    public int order() {
        return 30;
    }

    @Override
    public Mono<Optional<ExecutionPlan>> tryRoute(RoutingContext ctx) {
        return classifyWithOptionalIntentRewrite(ctx.userMessage(), ctx.traceMessageId())
                .map(Optional::of);
    }

    private Mono<ExecutionPlan> classifyWithOptionalIntentRewrite(String userMessage, String traceMessageId) {
        if (!queryRewriteService.shouldRewriteIntent(userMessage)) {
            return intentRouter.classifyPlan(userMessage);
        }
        return Mono.fromCallable(() -> queryRewriteService.rewriteForIntent(userMessage, traceMessageId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(outcome -> {
                    String query = StringUtils.hasText(outcome.effectiveQuery())
                            ? outcome.effectiveQuery()
                            : userMessage;
                    return intentRouter.classifyPlan(query);
                });
    }
}
