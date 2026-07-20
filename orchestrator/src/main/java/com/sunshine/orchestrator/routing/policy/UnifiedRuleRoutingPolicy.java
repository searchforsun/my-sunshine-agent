package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import com.sunshine.routing.RoutingRuleDef;
import com.sunshine.routing.UnifiedRuleEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 统一规则引擎路由（原 L1 structural + L1b peer + L2 regex）。
 * 规则 SSOT：prompt-manager Catalog → {@link PromptCatalogHolder}。
 */
@Component
@RequiredArgsConstructor
public class UnifiedRuleRoutingPolicy implements RoutingPolicy {

    private final PromptCatalogHolder holder;

    @Override
    public int order() {
        return 10;
    }

    @Override
    public Mono<Optional<ExecutionPlan>> tryRoute(RoutingContext ctx) {
        return Mono.fromCallable(() -> match(holder.snapshot().routingRules(), ctx.userMessage()));
    }

    /** ForcedExecutionRouter / SkillBinding 与链上 Policy 共用匹配入口 */
    public static Optional<ExecutionPlan> match(List<RoutingRuleDef> rules, String userMessage) {
        return new UnifiedRuleEngine(rules).match(userMessage).map(UnifiedRuleRoutingPolicy::toExecutionPlan);
    }

    /** Skill 5B：仅当命中 structural 规则时升为 plan-workflow */
    public static boolean looksLikeStructural(List<RoutingRuleDef> rules, String query) {
        return new UnifiedRuleEngine(rules).match(query)
                .filter(hit -> hit.reason() != null && hit.reason().startsWith("structural:"))
                .isPresent();
    }

    static ExecutionPlan toExecutionPlan(UnifiedRuleEngine.Hit hit) {
        ExecutionMode mode = parseMode(hit.plan() != null ? hit.plan().mode() : null);
        String workflowId = hit.plan() != null ? hit.plan().workflowId() : null;
        Map<String, String> params = hit.plan() != null && hit.plan().params() != null
                ? new LinkedHashMap<>(hit.plan().params())
                : Map.of();
        return new ExecutionPlan(mode, workflowId, params, "rule:" + hit.ruleId(), hit.ruleId());
    }

    private static ExecutionMode parseMode(String raw) {
        if (raw == null) {
            return ExecutionMode.WORKFLOW;
        }
        return switch (raw.toLowerCase()) {
            case "react" -> ExecutionMode.REACT;
            case "plan-workflow", "plan_workflow", "plan" -> ExecutionMode.PLAN_WORKFLOW;
            case "peer-collab", "peer_collab", "peer" -> ExecutionMode.PEER_COLLAB;
            default -> ExecutionMode.WORKFLOW;
        };
    }
}
