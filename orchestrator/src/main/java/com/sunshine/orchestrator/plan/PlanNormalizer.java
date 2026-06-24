package com.sunshine.orchestrator.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Planner 输出补全 — 缺 edges 时推断单链；固定拼接终态 answer（输入须为纯业务节点，无 start/answer）。
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
        String tailId = resolveTail(businessNodes, edges);
        List<PlanNode> nodes = new ArrayList<>(businessNodes);
        nodes.add(new PlanNode(ANSWER_NODE_ID, "answer", Map.of(), null));
        edges.add(new PlanEdge(tailId, ANSWER_NODE_ID));
        return new PlanJson(raw.planId(), raw.reason(), List.copyOf(nodes), List.copyOf(edges));
    }

    private static String resolveTail(List<PlanNode> businessNodes, List<PlanEdge> edges) {
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
}
