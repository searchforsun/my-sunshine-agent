package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.catalog.AgentBindingParser;
import com.sunshine.orchestrator.routing.policy.RoutingContext;
import com.sunshine.orchestrator.skill.SkillBindingParser;
import com.sunshine.orchestrator.skill.SkillDiscoveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 路由入口（v6）：用户 executionMode 钉死；经 {@link ForcedExecutionRouter} 分轨收集绑定，永不自判改 mode。
 * 收尾做 S-1 轻 sticky 合并（无新触发时继承上轮 triggered / 可调度集）。
 */
@Component
@RequiredArgsConstructor
public class ExecutionPlanRouter {

    private final SkillDiscoveryService skillDiscoveryService;
    private final ForcedExecutionRouter forcedExecutionRouter;
    private final SkillBindingParser skillBindingParser;
    private final AgentBindingParser agentBindingParser;
    private final RoutingStickyService routingStickyService;
    /** S-C：L3 逐项置信分 → 触发 ≤1 / 候选 / 可调度池（在 sanitize 之后、sticky 之前） */
    private final SkillAdoptionService skillAdoptionService;

    public Mono<ExecutionPlan> route(String userMessage) {
        return route(userMessage, null);
    }

    public Mono<ExecutionPlan> route(String userMessage, String traceMessageId) {
        return route(new RoutingContext(userMessage, traceMessageId));
    }

    public Mono<ExecutionPlan> route(RoutingContext ctx) {
        ExecutionMode preference = ctx.preference() != null ? ctx.preference() : ExecutionMode.FAST;
        RoutingContext routedCtx = routingContextForPinnedPreference(ctx, preference);
        return forcedExecutionRouter.resolve(routedCtx, preference, ctx.forcedWorkflowId())
                .map(plan -> preference == ExecutionMode.FAST
                        ? skillDiscoveryService.enrich(plan, ctx.tenantIdOrDefault())
                        : plan)
                .map(plan -> skillDiscoveryService.filterForTrack(plan, preference))
                .map(plan -> skillAdoptionService.apply(plan, ctx.tenantIdOrDefault(), ctx.kindOrDefault()))
                .map(plan -> routingStickyService.applySeed(plan, ctx.seed()));
    }

    /** WORKFLOW 钉死：路由与执行均忽略 /skill、$agent，仅保留正文；保留 kind */
    private RoutingContext routingContextForPinnedPreference(RoutingContext ctx, ExecutionMode preference) {
        if (preference.allowsSkillBinding()) {
            return ctx;
        }
        String plain = skillBindingParser.stripSlashMention(ctx.userMessage());
        plain = agentBindingParser.stripAgentMentions(plain);
        return ctx.withUserMessage(plain).withoutClientSkill();
    }
}
