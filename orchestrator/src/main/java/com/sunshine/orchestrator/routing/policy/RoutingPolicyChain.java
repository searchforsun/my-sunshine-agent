package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.routing.ExecutionPlan;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 按 order 串联 RoutingPolicy，首个命中即返回 */
@Component
@RequiredArgsConstructor
public class RoutingPolicyChain {

    private final List<RoutingPolicy> policies;

    public Mono<ExecutionPlan> route(RoutingContext ctx) {
        List<RoutingPolicy> sorted = policies.stream()
                .sorted(Comparator.comparingInt(RoutingPolicy::order))
                .toList();
        return Flux.fromIterable(sorted)
                .concatMap(p -> p.tryRoute(ctx))
                .filter(Optional::isPresent)
                .next()
                .map(Optional::get)
                .switchIfEmpty(Mono.error(new IllegalStateException("路由链未产出 ExecutionPlan")));
    }
}
