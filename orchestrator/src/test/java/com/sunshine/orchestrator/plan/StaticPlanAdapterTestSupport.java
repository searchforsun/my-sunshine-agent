package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.execution.WorkflowDefinition;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 测试辅助：线性 WorkflowDefinition → PlanJson（生产路径为 StaticPlanAdapter.fromStoredPlan） */
final class StaticPlanAdapterTestSupport {

    private StaticPlanAdapterTestSupport() {
    }

    static PlanJson from(WorkflowDefinition def, String routeReason) {
        List<PlanNode> nodes = new ArrayList<>();
        for (String nodeId : def.linearOrder()) {
            var spec = def.node(nodeId);
            if (spec == null) {
                continue;
            }
            nodes.add(new PlanNode(spec.id(), spec.type(), spec.params(), spec.displayName()));
        }
        List<PlanEdge> edges = new ArrayList<>();
        List<String> order = def.linearOrder();
        for (int i = 0; i < order.size() - 1; i++) {
            edges.add(new PlanEdge(order.get(i), order.get(i + 1)));
        }
        injectStart(nodes, edges);
        String reason = StringUtils.hasText(routeReason) ? routeReason.strip() : "静态工作流 " + def.id();
        return new PlanJson(null, reason, List.copyOf(nodes), List.copyOf(edges));
    }

    private static void injectStart(List<PlanNode> nodes, List<PlanEdge> edges) {
        if (nodes.isEmpty()) {
            return;
        }
        boolean hasStart = nodes.stream().anyMatch(n -> "start".equals(n.id()) || "start".equals(n.type()));
        if (!hasStart) {
            nodes.add(0, new PlanNode("start", "start", Map.of(), "开始"));
        }
        boolean hasStartEdge = edges.stream().anyMatch(e -> "start".equals(e.from()));
        if (!hasStartEdge) {
            String firstBiz = nodes.stream()
                    .filter(n -> !"start".equals(n.type()))
                    .map(PlanNode::id)
                    .findFirst()
                    .orElse(null);
            if (firstBiz != null) {
                edges.add(0, new PlanEdge("start", firstBiz));
            }
        }
    }
}
