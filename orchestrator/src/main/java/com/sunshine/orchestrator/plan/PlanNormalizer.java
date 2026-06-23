package com.sunshine.orchestrator.plan;

import java.util.ArrayList;
import java.util.List;

/** Planner 输出补全 — 缺 edges 时按 nodes 顺序推断单链 */
public final class PlanNormalizer {

    private PlanNormalizer() {
    }

    public static PlanJson normalize(PlanJson raw) {
        if (raw == null || raw.nodes().isEmpty() || !raw.edges().isEmpty()) {
            return raw;
        }
        List<PlanNode> chain = raw.nodes().stream()
                .filter(n -> !"start".equals(n.type()))
                .toList();
        if (chain.size() <= 1) {
            return raw;
        }
        List<PlanEdge> edges = new ArrayList<>();
        edges.add(new PlanEdge("start", chain.get(0).id()));
        for (int i = 0; i < chain.size() - 1; i++) {
            edges.add(new PlanEdge(chain.get(i).id(), chain.get(i + 1).id()));
        }
        return new PlanJson(raw.planId(), raw.reason(), raw.nodes(), List.copyOf(edges));
    }
}
