package com.sunshine.orchestrator.plan;

/** Planner / Studio 有向边；条件字段仅用于 exclusive-gateway 出边 */
public record PlanEdge(
        String from,
        String to,
        PlanEdgeCondition condition,
        boolean isDefault) {

    public PlanEdge(String from, String to) {
        this(from, to, null, false);
    }

    public PlanEdge {
        if (condition != null && condition.op().isBlank() && condition.left().isBlank() && condition.right().isBlank()) {
            condition = null;
        }
    }

    public boolean hasCondition() {
        return condition != null && condition.isComplete();
    }
}
