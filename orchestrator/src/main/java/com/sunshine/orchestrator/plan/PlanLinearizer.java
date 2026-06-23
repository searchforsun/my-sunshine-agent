package com.sunshine.orchestrator.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将 Plan edges 线性化为执行顺序（MVP：单链拓扑） */
public final class PlanLinearizer {

    private PlanLinearizer() {
    }

    public static List<String> linearOrder(PlanJson plan) {
        Map<String, List<String>> adj = new HashMap<>();
        for (PlanEdge edge : plan.edges()) {
            adj.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
        }
        String start = adj.containsKey("start") ? "start" : findRoot(plan, adj);
        List<String> order = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        walk(start, adj, visited, order);
        if (order.isEmpty()) {
            return plan.nodes().stream().map(PlanNode::id).toList();
        }
        return List.copyOf(order);
    }

    private static String findRoot(PlanJson plan, Map<String, List<String>> adj) {
        Set<String> hasIncoming = new HashSet<>();
        for (List<String> targets : adj.values()) {
            hasIncoming.addAll(targets);
        }
        for (PlanNode node : plan.nodes()) {
            if (!hasIncoming.contains(node.id())) {
                return node.id();
            }
        }
        if (plan.nodes().isEmpty()) {
            return "start";
        }
        return plan.nodes().get(0).id();
    }

    private static void walk(
            String current,
            Map<String, List<String>> adj,
            Set<String> visited,
            List<String> order) {
        if (current == null || visited.contains(current)) {
            return;
        }
        if (!"start".equals(current)) {
            order.add(current);
        }
        visited.add(current);
        List<String> next = adj.getOrDefault(current, List.of());
        if (next.size() == 1) {
            walk(next.get(0), adj, visited, order);
        } else {
            for (String target : next) {
                walk(target, adj, visited, order);
            }
        }
    }
}
