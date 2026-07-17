package com.sunshine.orchestrator.plan;

import com.sunshine.common.workflow.WorkflowNodeType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Planner 输出补全 — 缺 edges 时推断单链；多 sink 时各接 answer；固定拼接终态 answer。
 * 输入须无 start/answer（Planner 不产出）。
 */
public final class PlanNormalizer {

    /** 引擎固定终态 answer 节点 id（与 start 对称，Planner 不产出） */
    public static final String ANSWER_NODE_ID = "answer";

    private PlanNormalizer() {
    }

    public static PlanJson normalize(PlanJson raw) {
        if (raw == null || raw.nodes().isEmpty()) {
            return raw;
        }
        List<PlanNode> businessNodes = raw.nodes();
        List<PlanEdge> edges = new ArrayList<>(raw.edges());
        if (edges.isEmpty()) {
            edges.add(new PlanEdge("start", businessNodes.get(0).id()));
            for (int i = 0; i < businessNodes.size() - 1; i++) {
                edges.add(new PlanEdge(businessNodes.get(i).id(), businessNodes.get(i + 1).id()));
            }
        }
        List<PlanNode> nodes = new ArrayList<>(businessNodes);
        nodes.add(new PlanNode(ANSWER_NODE_ID, "answer", Map.of(), null));
        for (String sinkId : resolveSinkIds(businessNodes, edges)) {
            edges.add(new PlanEdge(sinkId, ANSWER_NODE_ID));
        }
        return new PlanJson(raw.planId(), raw.reason(), List.copyOf(nodes), List.copyOf(edges), raw.layout());
    }

    /** 归一化前图的 sink 节点（无出边的外图节点）；loop body 由 loop 容器统一接 answer */
    private static List<String> resolveSinkIds(List<PlanNode> nodes, List<PlanEdge> edges) {
        Set<String> hasOutgoing = new HashSet<>();
        for (PlanEdge edge : edges) {
            hasOutgoing.add(edge.from());
        }
        List<String> sinks = new ArrayList<>();
        for (PlanNode node : nodes) {
            if (node.hasParent()) {
                continue;
            }
            if (!hasOutgoing.contains(node.id())) {
                sinks.add(node.id());
            }
        }
        if (sinks.isEmpty()) {
            sinks.add(resolveTailLinear(nodes, edges));
        }
        return List.copyOf(sinks);
    }

    private static String resolveTailLinear(List<PlanNode> businessNodes, List<PlanEdge> edges) {
        PlanJson temp = new PlanJson("tmp", "", businessNodes, edges);
        List<String> order = PlanLinearizer.linearOrder(temp);
        for (int i = order.size() - 1; i >= 0; i--) {
            String id = order.get(i);
            if (!"start".equals(id)) {
                return id;
            }
        }
        return businessNodes.get(businessNodes.size() - 1).id();
    }

    /** 节点数上限计数：仅业务节点 + loop 容器（路由网关/join 不计） */
    public static long countPlannerComplexityNodes(PlanJson plan) {
        if (plan == null || plan.nodes().isEmpty()) {
            return 0;
        }
        return plan.nodes().stream()
                .filter(n -> WorkflowNodeType.plannerBusinessTypeIds().contains(n.type())
                        || WorkflowNodeType.LOOP.id().equals(n.type()))
                .count();
    }
}
