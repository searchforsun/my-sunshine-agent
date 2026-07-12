package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowDefinition;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 静态 Nacos Workflow 定义 → 可持久化 PlanJson（与动态 Plan 同构，供 DAG 展示与审计） */
public final class StaticPlanAdapter {

    private StaticPlanAdapter() {
    }

    public static PlanJson from(WorkflowDefinition def, String routeReason) {
        List<PlanNode> nodes = new ArrayList<>();
        for (String nodeId : def.linearOrder()) {
            NodeSpec spec = def.node(nodeId);
            if (spec == null) {
                continue;
            }
            nodes.add(new PlanNode(
                    spec.id(), spec.type(), spec.params(), spec.displayName()));
        }
        List<PlanEdge> edges = new ArrayList<>();
        List<String> order = def.linearOrder();
        for (int i = 0; i < order.size() - 1; i++) {
            edges.add(new PlanEdge(order.get(i), order.get(i + 1)));
        }
        injectStartForDag(nodes, edges);
        String reason = StringUtils.hasText(routeReason)
                ? routeReason.strip()
                : "静态工作流 " + def.id();
        // planId 留空，由 ExecutionPlanStore 生成 UUID，避免同 workflow 多次执行主键冲突
        return new PlanJson(null, reason, List.copyOf(nodes), List.copyOf(edges));
    }

    /** 执行链不含 start（PlanMaterializer 已剔除），DAG 展示须补回与 Studio 一致的起点 */
    private static void injectStartForDag(List<PlanNode> nodes, List<PlanEdge> edges) {
        if (nodes.isEmpty()) {
            return;
        }
        boolean hasStart = nodes.stream()
                .anyMatch(n -> "start".equals(n.id()) || "start".equals(n.type()));
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
