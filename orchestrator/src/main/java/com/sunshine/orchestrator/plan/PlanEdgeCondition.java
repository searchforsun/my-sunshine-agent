package com.sunshine.orchestrator.plan;

/** 排他网关出边条件（结构化算子，无脚本） */
public record PlanEdgeCondition(String left, String op, String right) {

    public PlanEdgeCondition {
        left = left != null ? left : "";
        op = op != null ? op.strip().toLowerCase() : "";
        right = right != null ? right : "";
    }

    public boolean isComplete() {
        if (op.isBlank()) {
            return false;
        }
        if ("empty".equals(op) || "not_empty".equals(op)) {
            return !left.isBlank();
        }
        return !left.isBlank() && ("contains".equals(op) || "eq".equals(op));
    }
}
