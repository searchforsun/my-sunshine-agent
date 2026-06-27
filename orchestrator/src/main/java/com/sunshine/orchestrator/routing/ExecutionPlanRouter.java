package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.routing.policy.RoutingPolicyChain;
import com.sunshine.orchestrator.skill.SkillBindingParser;
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
    private final SkillBindingParser skillBindingParser;

    public Mono<ExecutionPlan> route(String userMessage) {
        return route(userMessage, null);
    }

    public Mono<ExecutionPlan> route(String userMessage, String traceMessageId) {
        return route(new RoutingContext(userMessage, traceMessageId));
    }

    public Mono<ExecutionPlan> route(RoutingContext ctx) {
        ExecutionPreference preference = ctx.preference() != null ? ctx.preference() : ExecutionPreference.AUTO;
        RoutingContext routedCtx = routingContextForForcedPreference(ctx, preference);
        if (preference.isForced()) {
            return forcedExecutionRouter.resolve(routedCtx, preference, ctx.forcedWorkflowId())
                    .map(plan -> preference == ExecutionPreference.REACT
                            ? skillDiscoveryService.enrich(plan, routedCtx.userMessage())
                            : plan);
        }
        return policyChain.route(ctx)
                .map(plan -> skillDiscoveryService.enrich(plan, ctx.userMessage()));
    }

    /** 禁用 Skill 的强制模式：路由与执行均忽略 @skill，仅保留正文 */
    private RoutingContext routingContextForForcedPreference(RoutingContext ctx, ExecutionPreference preference) {
        if (!preference.isForced() || preference.allowsSkillBinding()) {
            return ctx;
        }
        String plain = skillBindingParser.stripAtMention(ctx.userMessage());
        return new RoutingContext(
                plain, ctx.traceMessageId(), ctx.preference(), ctx.forcedWorkflowId(), null, ctx.memory());
    }
}
