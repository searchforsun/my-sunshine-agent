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
 * 规则 SSOT：prompt-manager Catalog → {@link PromptCatalogHolder}。
 * 分轨：轨 A 仅 FAST/PRO 规则；轨 B 仅 WORKFLOW；产出 plan 的 mode 由 lockedMode 钉死。
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
        ExecutionMode locked = ctx.effectiveLockedMode();
        return Mono.fromCallable(() -> matchForLockedMode(holder.snapshot().routingRules(), ctx.userMessage(), locked)
                .map(plan -> withLockedMode(plan, locked)));
    }

    /** ForcedExecutionRouter / SkillBinding 与链上 Policy 共用匹配入口（无分轨） */
    public static Optional<ExecutionPlan> match(List<RoutingRuleDef> rules, String userMessage) {
        return new UnifiedRuleEngine(rules).match(userMessage).map(UnifiedRuleRoutingPolicy::toExecutionPlan);
    }

    /**
     * 分轨匹配：先按 lockedMode 过滤规则再跑引擎，避免错误轨的高优规则抢占。
     */
    public static Optional<ExecutionPlan> matchForLockedMode(
            List<RoutingRuleDef> rules, String userMessage, ExecutionMode locked) {
        if (locked == null) {
            return match(rules, userMessage);
        }
        List<RoutingRuleDef> filtered = rules.stream()
                .filter(r -> isRuleCompatible(r, locked))
                .toList();
        return match(filtered, userMessage);
    }

    /** Skill 5B：仅当命中 structural 规则时升为 plan-workflow */
    public static boolean looksLikeStructural(List<RoutingRuleDef> rules, String query) {
        return new UnifiedRuleEngine(rules).match(query)
                .filter(hit -> hit.reason() != null && hit.reason().startsWith("structural:"))
                .isPresent();
    }

    static boolean isRuleCompatible(RoutingRuleDef rule, ExecutionMode locked) {
        ExecutionMode ruleMode = parseMode(rule.plan() != null ? rule.plan().mode() : null);
        if (locked == ExecutionMode.WORKFLOW) {
            return ruleMode == ExecutionMode.WORKFLOW;
        }
        // 轨 A：同 locked mode（FAST↔react、PRO↔plan-workflow），永不收 workflow 规则
        return ruleMode == locked;
    }

    static ExecutionPlan toExecutionPlan(UnifiedRuleEngine.Hit hit) {
        ExecutionMode mode = parseMode(hit.plan() != null ? hit.plan().mode() : null);
        String workflowId = hit.plan() != null ? hit.plan().workflowId() : null;
        Map<String, String> params = hit.plan() != null && hit.plan().params() != null
                ? new LinkedHashMap<>(hit.plan().params())
                : Map.of();
        return new ExecutionPlan(mode, workflowId, params, "rule:" + hit.ruleId(), hit.ruleId());
    }

    private static ExecutionPlan withLockedMode(ExecutionPlan plan, ExecutionMode locked) {
        if (plan == null || locked == null || plan.mode() == locked) {
            return plan;
        }
        return new ExecutionPlan(locked, plan.workflowId(), plan.params(), plan.reason(), plan.ruleId());
    }

    static ExecutionMode parseMode(String raw) {
        if (raw == null) {
            return ExecutionMode.WORKFLOW;
        }
        return switch (raw.toLowerCase().replace('_', '-')) {
            case "fast", "react", "agent", "auto" -> ExecutionMode.FAST;
            case "pro", "plan-workflow", "plan" -> ExecutionMode.PRO;
            default -> ExecutionMode.WORKFLOW;
        };
    }
}
