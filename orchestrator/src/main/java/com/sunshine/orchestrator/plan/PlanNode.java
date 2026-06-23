package com.sunshine.orchestrator.plan;

import java.util.Map;

/** Planner 输出的单个 DAG 节点 */
public record PlanNode(
        String id,
        String type,
        Map<String, String> params,
        String displayName
) {
    public PlanNode {
        params = params != null ? Map.copyOf(params) : Map.of();
    }

    public PlanNode(String id, String type, Map<String, String> params) {
        this(id, type, params, null);
    }
}
