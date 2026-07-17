package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.plan.PlanEdgeCondition;
import org.springframework.util.StringUtils;

/** 排他网关边条件求值（empty / not_empty / contains / eq） */
public final class EdgeConditionEvaluator {

    private EdgeConditionEvaluator() {
    }

    public static boolean matches(PlanEdgeCondition condition, WorkflowContext ctx) {
        if (condition == null || !condition.isComplete()) {
            return false;
        }
        String left = TemplateResolver.resolve(condition.left(), ctx);
        String right = TemplateResolver.resolve(condition.right(), ctx);
        String op = condition.op();
        return switch (op) {
            case "empty" -> !StringUtils.hasText(left);
            case "not_empty" -> StringUtils.hasText(left);
            case "contains" -> left != null && right != null && left.contains(right);
            case "eq" -> normalize(left).equals(normalize(right));
            default -> false;
        };
    }

    private static String normalize(String s) {
        return s == null ? "" : s.strip();
    }
}
