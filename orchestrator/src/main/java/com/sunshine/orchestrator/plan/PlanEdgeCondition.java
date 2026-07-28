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
        // 数值/枚举类算子只需 left，比较/包含类算子需 left + right
        if ("gt".equals(op) || "lt".equals(op) || "gte".equals(op)
                || "lte".equals(op) || "in".equals(op) || "not_in".equals(op)) {
            return !left.isBlank();
        }
        // eq / not_eq / contains / not_contains 需 left + right
        return !left.isBlank() && !right.isBlank();
    }
}
