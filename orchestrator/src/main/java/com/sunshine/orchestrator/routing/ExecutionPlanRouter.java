package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.skill.SkillBindingParser;
import com.sunshine.orchestrator.skill.SkillDiscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 路由入口（v6）：用户 executionMode 钉死；经 {@link ForcedExecutionRouter} 收集绑定，永不自判改 mode。
 */
@Component
@RequiredArgsConstructor
public class ExecutionPlanRouter {

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
        ExecutionPreference preference = ctx.preference() != null ? ctx.preference() : ExecutionPreference.FAST;
        RoutingContext routedCtx = routingContextForPinnedPreference(ctx, preference);
        return forcedExecutionRouter.resolve(routedCtx, preference, ctx.forcedWorkflowId())
                .map(plan -> preference == ExecutionPreference.FAST
                        ? skillDiscoveryService.enrich(plan, routedCtx.userMessage())
                        : plan);
    }

    /** WORKFLOW 钉死：路由与执行均忽略 @skill，仅保留正文 */
    private RoutingContext routingContextForPinnedPreference(RoutingContext ctx, ExecutionPreference preference) {
        if (preference.allowsSkillBinding()) {
            return ctx;
        }
        String plain = skillBindingParser.stripAtMention(ctx.userMessage());
        return new RoutingContext(
                plain, ctx.traceMessageId(), ctx.preference(), ctx.forcedWorkflowId(), null, ctx.memory(),
                ctx.lockedMode());
    }
}
