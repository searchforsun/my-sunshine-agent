package com.sunshine.orchestrator.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.plan.PlanEdgeCondition;
import org.springframework.util.StringUtils;

import java.util.List;

/** 排他网关边条件求值（empty / not_empty / contains / eq / gt / lt / gte / lte / in / not_in） */
public final class EdgeConditionEvaluator {

    private static final ObjectMapper OM = new ObjectMapper();

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
            case "gt" -> toDouble(left) > toDouble(right);
            case "lt" -> toDouble(left) < toDouble(right);
            case "gte" -> toDouble(left) >= toDouble(right);
            case "lte" -> toDouble(left) <= toDouble(right);
            case "in" -> parseJsonArray(right).contains(normalize(left));
            case "not_in" -> !parseJsonArray(right).contains(normalize(left));
            default -> false;
        };
    }

    private static String normalize(String s) {
        return s == null ? "" : s.strip();
    }

    private static double toDouble(String s) {
        if (s == null || s.isBlank()) {
            return Double.NEGATIVE_INFINITY;
        }
        try {
            return Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            return Double.NEGATIVE_INFINITY;
        }
    }

    private static List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OM.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
