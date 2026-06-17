package com.sunshine.orchestrator.execution;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 从 Nacos 加载的 Workflow 图定义
 */
public record WorkflowDefinition(
        String id,
        Map<String, NodeSpec> nodesById,
        List<String> linearOrder
) {
    public NodeSpec node(String nodeId) {
        return nodesById.get(nodeId);
    }

    public static WorkflowDefinition from(String id, List<NodeSpec> nodes, List<String> linearOrder) {
        Map<String, NodeSpec> map = nodes.stream()
                .collect(Collectors.toMap(NodeSpec::id, Function.identity(), (a, b) -> a, java.util.LinkedHashMap::new));
        return new WorkflowDefinition(id, map, linearOrder);
    }
}
