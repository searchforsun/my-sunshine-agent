package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.execution.InputBinding;

import java.util.List;
import java.util.Map;

/** Planner / Studio 输出的单个 DAG 节点；body 节点可带 parentId 归属 loop 容器 */
public record PlanNode(
        String id,
        String type,
        Map<String, Object> params,
        List<InputBinding> inputs,
        String displayName,
        String parentId
) {
    public PlanNode {
        params = params != null ? Map.copyOf(params) : Map.of();
        inputs = inputs != null ? List.copyOf(inputs) : List.of();
    }

    public PlanNode(String id, String type, Map<String, Object> params) {
        this(id, type, params, List.of(), null, null);
    }

    public PlanNode(String id, String type, Map<String, Object> params, String displayName) {
        this(id, type, params, List.of(), displayName, null);
    }

    public PlanNode(String id, String type, Map<String, Object> params, String displayName, String parentId) {
        this(id, type, params, List.of(), displayName, parentId);
    }

    public boolean hasParent() {
        return parentId != null && !parentId.isBlank();
    }
}
