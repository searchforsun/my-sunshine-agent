package com.sunshine.orchestrator.plan;

import java.util.Map;

/** Planner / Studio 输出的单个 DAG 节点；body 节点可带 parentId 归属 loop 容器 */
public record PlanNode(
        String id,
        String type,
        Map<String, String> params,
        String displayName,
        String parentId
) {
    public PlanNode {
        params = params != null ? Map.copyOf(params) : Map.of();
    }

    public PlanNode(String id, String type, Map<String, String> params) {
        this(id, type, params, null, null);
    }

    public PlanNode(String id, String type, Map<String, String> params, String displayName) {
        this(id, type, params, displayName, null);
    }

    public boolean hasParent() {
        return parentId != null && !parentId.isBlank();
    }
}
