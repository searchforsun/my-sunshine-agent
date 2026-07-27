package com.sunshine.orchestrator.plan;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** workflow-manager plan_json → execution_plan 快照（保留真实 DAG 与 layout） */
public final class StaticPlanAdapter {

    private StaticPlanAdapter() {
    }

    /** DB 已发布 plan_json → 执行计划展示快照（planId 留空由 store 生成） */
    public static PlanJson fromStoredPlan(PlanJson source, String routeReason) {
        List<PlanNode> nodes = new ArrayList<>(source.nodes());
        List<PlanEdge> edges = new ArrayList<>(source.edges());
        injectStartForDag(nodes, edges);
        String reason = StringUtils.hasText(routeReason)
                ? routeReason.strip()
                : (StringUtils.hasText(source.reason()) ? source.reason().strip() : "静态工作流");
        return new PlanJson(null, reason, List.copyOf(nodes), List.copyOf(edges), source.layout());
    }

    /** 执行链不含 start 时补回与 Studio 一致的起点 */
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
