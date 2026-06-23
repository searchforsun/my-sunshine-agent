package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.routing.ExecutionPlan;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * 路由策略 — 返回 empty 则继续下一策略。
 * 顺序：Skill(0) → Structural(10) → GoldenRule(20) → LlmClassifier(30)
 */
public interface RoutingPolicy {

    int order();

    Mono<Optional<ExecutionPlan>> tryRoute(RoutingContext ctx);
}
