package com.sunshine.orchestrator.plan;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Planner LLM 输出的结构化执行计划 */
public record PlanJson(
        String planId,
        String reason,
        List<PlanNode> nodes,
        List<PlanEdge> edges
) {
    public PlanJson {
        nodes = nodes != null ? List.copyOf(nodes) : List.of();
        edges = edges != null ? List.copyOf(edges) : List.of();
    }

    public Map<String, PlanNode> nodesById() {
        return nodes.stream()
                .collect(Collectors.toMap(PlanNode::id, Function.identity(), (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
