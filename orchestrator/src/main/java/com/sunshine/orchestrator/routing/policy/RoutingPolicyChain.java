package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 按 order 串联 RoutingPolicy。v6：入口钉死 mode，各 Policy 只填绑定，链尾再次锁 mode。
 * 生产主路径已改走 {@code ForcedExecutionRouter}；本链保留供试跑/兼容，禁止改写 executionMode。
 */
@Component
@RequiredArgsConstructor
public class RoutingPolicyChain {

    private final List<RoutingPolicy> policies;

    public Mono<ExecutionPlan> route(RoutingContext ctx) {
        ExecutionMode locked = ctx.effectiveLockedMode();
        RoutingContext pinned = ctx.withLockedMode(locked);
        List<RoutingPolicy> sorted = policies.stream()
                .sorted(Comparator.comparingInt(RoutingPolicy::order))
                .toList();
        return Flux.fromIterable(sorted)
                .concatMap(p -> p.tryRoute(pinned))
                .filter(Optional::isPresent)
                .next()
                .map(Optional::get)
                .map(plan -> IntentRouter.applyLockedMode(plan, locked))
                .switchIfEmpty(Mono.error(new IllegalStateException("路由链未产出 ExecutionPlan")));
    }
}
