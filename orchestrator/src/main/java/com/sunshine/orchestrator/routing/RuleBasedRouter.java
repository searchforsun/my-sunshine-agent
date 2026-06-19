package com.sunshine.orchestrator.routing;

import com.sunshine.orchestrator.config.RoutingRuleProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** 规则硬路由 — 命中则直接产出 ExecutionPlan，跳过 LLM 意图分类 */
@Component
@RequiredArgsConstructor
public class RuleBasedRouter {

    private final RoutingRuleProperties properties;

    public Optional<ExecutionPlan> match(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return Optional.empty();
        }
        String query = userQuery.strip();
        List<RoutingRuleProperties.Rule> sorted = properties.getRules().stream()
                .sorted(Comparator.comparingInt(RoutingRuleProperties.Rule::getPriority).reversed())
                .toList();
        for (RoutingRuleProperties.Rule rule : sorted) {
            if (matchesRule(query, rule)) {
                return Optional.of(toPlan(rule));
            }
        }
        return Optional.empty();
    }

    private static boolean matchesRule(String query, RoutingRuleProperties.Rule rule) {
        List<String> patterns = rule.getPatterns();
        if (patterns == null || patterns.isEmpty()) {
            return false;
        }
        boolean all = "all".equalsIgnoreCase(rule.getMatch());
        for (String raw : patterns) {
            boolean hit = Pattern.compile(raw).matcher(query).find();
            if (all && !hit) {
                return false;
            }
            if (!all && hit) {
                return true;
            }
        }
        return all;
    }

    private static ExecutionPlan toPlan(RoutingRuleProperties.Rule rule) {
        RoutingRuleProperties.PlanSpec spec = rule.getPlan();
        ExecutionMode mode = parseMode(spec != null ? spec.getMode() : null);
        String workflowId = spec != null ? spec.getWorkflowId() : null;
        Map<String, String> params = spec != null && spec.getParams() != null
                ? new LinkedHashMap<>(spec.getParams())
                : Map.of();
        return new ExecutionPlan(mode, workflowId, params, "rule:" + rule.getId(), rule.getId());
    }

    private static ExecutionMode parseMode(String raw) {
        if (raw == null) {
            return ExecutionMode.WORKFLOW;
        }
        return switch (raw.toLowerCase()) {
            case "react" -> ExecutionMode.REACT;
            case "simple-llm", "simple_llm", "simple" -> ExecutionMode.SIMPLE_LLM;
            default -> ExecutionMode.WORKFLOW;
        };
    }
}
