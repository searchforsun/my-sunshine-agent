package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.config.VirtualThreadExecutors;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.orchestrator.rewrite.QueryRewriteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * L3：Intent 改写 + LLM 意图分类 — 仅填充绑定；mode 恒为 ctx.effectiveLockedMode()。
 */
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
        ExecutionMode locked = ctx.effectiveLockedMode();
        return classifyWithOptionalIntentRewrite(ctx.withLockedMode(locked))
                .map(Optional::of);
    }

    private Mono<ExecutionPlan> classifyWithOptionalIntentRewrite(RoutingContext ctx) {
        String userMessage = ctx.userMessage();
        if (!queryRewriteService.shouldRewriteIntent(userMessage)) {
            return intentRouter.classifyPlan(ctx);
        }
        return Mono.fromCallable(() -> queryRewriteService.rewriteForIntent(
                        userMessage, ctx.traceMessageId(), ctx.memory()))
                .subscribeOn(VirtualThreadExecutors.scheduler())
                .flatMap(outcome -> {
                    String query = StringUtils.hasText(outcome.effectiveQuery())
                            ? outcome.effectiveQuery()
                            : userMessage;
                    return intentRouter.classifyPlan(ctx.withUserMessage(query));
                });
    }
}
