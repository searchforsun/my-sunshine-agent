package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.plan.PlanExecutionSchedule;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Workflow 图定义（DB published plan 物化）
 */
public record WorkflowDefinition(
        String id,
        Map<String, NodeSpec> nodesById,
        List<String> linearOrder,
        List<PlanExecutionSchedule.Step> executionSteps
) {
    public WorkflowDefinition {
        executionSteps = executionSteps != null ? List.copyOf(executionSteps) : List.of();
    }

    public NodeSpec node(String nodeId) {
        return nodesById.get(nodeId);
    }

    public static WorkflowDefinition from(String id, List<NodeSpec> nodes, List<String> linearOrder) {
        return from(id, nodes, linearOrder, List.of());
    }

    public static WorkflowDefinition from(
            String id,
            List<NodeSpec> nodes,
            List<String> linearOrder,
            List<PlanExecutionSchedule.Step> executionSteps) {
        Map<String, NodeSpec> map = nodes.stream()
                .collect(Collectors.toMap(NodeSpec::id, Function.identity(), (a, b) -> a, java.util.LinkedHashMap::new));
        return new WorkflowDefinition(id, map, linearOrder, executionSteps);
    }
}
