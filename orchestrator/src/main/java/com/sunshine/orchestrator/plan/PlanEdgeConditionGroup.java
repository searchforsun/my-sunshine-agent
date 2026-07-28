package com.sunshine.orchestrator.plan;

import java.util.List;

/** 条件组：多条件 + AND/OR 组合（loop 继续条件 / exclusive-gateway 出边条件共用） */
public record PlanEdgeConditionGroup(
        String logic,
        List<PlanEdgeCondition> items) {

    public PlanEdgeConditionGroup {
        logic = (logic == null || logic.isBlank()) ? "and" : logic.strip().toLowerCase();
        items = items != null ? List.copyOf(items) : List.of();
    }

    public static PlanEdgeConditionGroup single(PlanEdgeCondition c) {
        return new PlanEdgeConditionGroup("and", c != null ? List.of(c) : List.of());
    }

    public static PlanEdgeConditionGroup empty() {
        return new PlanEdgeConditionGroup("and", List.of());
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
