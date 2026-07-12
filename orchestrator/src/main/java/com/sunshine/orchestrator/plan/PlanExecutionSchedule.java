package com.sunshine.orchestrator.plan;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plan DAG → 执行调度（串行 + 并行 fan-out/join） */
public final class PlanExecutionSchedule {

    private PlanExecutionSchedule() {
    }

    public sealed interface Step permits Single, Parallel {
    }

    public record Single(String nodeId) implements Step {
    }

    public record Parallel(List<String> branchNodeIds, String joinNodeId) implements Step {
        public Parallel {
            branchNodeIds = List.copyOf(branchNodeIds);
        }
    }

    /** 并行拓扑校验；无 join 时返回 null */
    public static String validateParallelTopology(PlanJson plan) {
        if (plan == null || plan.nodes().isEmpty()) {
            return "nodes 为空";
        }
        Map<String, PlanNode> nodes = plan.nodesById();
        Map<String, List<String>> out = outAdj(plan);
        Map<String, List<String>> in = inAdj(plan);
        List<String> joinIds = plan.nodes().stream()
                .filter(n -> "join".equals(n.type()))
                .map(PlanNode::id)
                .toList();
        if (joinIds.isEmpty()) {
            return null;
        }
        for (String joinId : joinIds) {
            List<String> preds = in.getOrDefault(joinId, List.of());
            if (preds.size() < 2) {
                return "join 节点 " + joinId + " 入度须 ≥ 2";
            }
            List<String> afterJoin = out.getOrDefault(joinId, List.of());
            if (afterJoin.size() != 1) {
                return "join 节点 " + joinId + " 出度须为 1";
            }
            String forkId = findFanOutFork(preds, in);
            if (!StringUtils.hasText(forkId)) {
                return "join 节点 " + joinId + " 缺少公共 fan-out 分叉点";
            }
            List<String> forkOut = out.getOrDefault(forkId, List.of());
            if (forkOut.size() < 2) {
                return "并行分叉点 " + forkId + " 出度须 ≥ 2";
            }
            for (String head : forkOut) {
                PlanNode headNode = nodes.get(head);
                if (headNode == null) {
                    return "并行分支头节点不存在: " + head;
                }
                if ("join".equals(headNode.type())) {
                    return "并行分支头不能为 join: " + head;
                }
                String convergingJoin = singlePathJoin(head, out, nodes, new HashSet<>());
                if (!joinId.equals(convergingJoin)) {
                    return "并行分支 " + head + " 须汇合至 join " + joinId;
                }
            }
            for (String pred : preds) {
                if (!reachableFromFork(forkId, pred, out, nodes, joinId)) {
                    return "join 前驱 " + pred + " 不在分叉点 " + forkId + " 的并行子树内";
                }
            }
        }
        return null;
    }

    /** 构建执行调度；无 join 时退化为线性 Single 链 */
    public static List<Step> build(PlanJson plan) {
        if (plan == null || plan.nodes().isEmpty()) {
            return List.of();
        }
        boolean hasJoin = plan.nodes().stream().anyMatch(n -> "join".equals(n.type()));
        if (!hasJoin) {
            return PlanLinearizer.linearOrder(plan).stream()
                    .filter(id -> !"start".equals(id))
                    .<Step>map(Single::new)
                    .toList();
        }
        Map<String, PlanNode> nodes = plan.nodesById();
        Map<String, List<String>> out = outAdj(plan);
        List<Step> steps = new ArrayList<>();
        String cursor = "start";
        while (true) {
            List<String> nexts = out.getOrDefault(cursor, List.of());
            if (nexts.isEmpty()) {
                break;
            }
            if (nexts.size() > 1) {
                String joinId = findConvergingJoin(nexts, out, nodes);
                if (!StringUtils.hasText(joinId)) {
                    return List.of();
                }
                steps.add(new Parallel(nexts, joinId));
                List<String> afterJoin = out.getOrDefault(joinId, List.of());
                if (afterJoin.isEmpty()) {
                    break;
                }
                String after = afterJoin.get(0);
                if (!"start".equals(after)) {
                    steps.add(new Single(after));
                }
                PlanNode afterNode = nodes.get(after);
                if (afterNode != null && "answer".equals(afterNode.type())) {
                    break;
                }
                cursor = after;
                continue;
            }
            String next = nexts.get(0);
            if (!"start".equals(next)) {
                steps.add(new Single(next));
            }
            cursor = next;
            PlanNode node = nodes.get(next);
            if (node != null && "answer".equals(node.type())) {
                break;
            }
        }
        return List.copyOf(steps);
    }

    public static List<String> flattenLinearOrder(List<Step> steps) {
        List<String> order = new ArrayList<>();
        for (Step step : steps) {
            if (step instanceof Single s) {
                order.add(s.nodeId());
            } else if (step instanceof Parallel p) {
                order.addAll(p.branchNodeIds());
                order.add(p.joinNodeId());
            }
        }
        return List.copyOf(order);
    }

    private static String findConvergingJoin(
            List<String> branchHeads,
            Map<String, List<String>> out,
            Map<String, PlanNode> nodes) {
        String joinId = null;
        for (String head : branchHeads) {
            String converging = singlePathJoin(head, out, nodes, new HashSet<>());
            if (!StringUtils.hasText(converging)) {
                return null;
            }
            if (joinId == null) {
                joinId = converging;
            } else if (!joinId.equals(converging)) {
                return null;
            }
        }
        return joinId;
    }

    private static String singlePathJoin(
            String nodeId,
            Map<String, List<String>> out,
            Map<String, PlanNode> nodes,
            Set<String> visiting) {
        if (!StringUtils.hasText(nodeId) || visiting.contains(nodeId)) {
            return null;
        }
        visiting.add(nodeId);
        PlanNode node = nodes.get(nodeId);
        if (node != null && "join".equals(node.type())) {
            return nodeId;
        }
        List<String> nexts = out.getOrDefault(nodeId, List.of());
        if (nexts.size() != 1) {
            return null;
        }
        return singlePathJoin(nexts.get(0), out, nodes, visiting);
    }

    private static String findFanOutFork(List<String> joinPreds, Map<String, List<String>> inAdj) {
        Set<String> candidates = new HashSet<>(inAdj.getOrDefault(joinPreds.get(0), List.of()));
        for (int i = 1; i < joinPreds.size(); i++) {
            candidates.retainAll(inAdj.getOrDefault(joinPreds.get(i), List.of()));
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.stream().sorted().findFirst().orElse(null);
    }

    private static boolean reachableFromFork(
            String forkId,
            String targetId,
            Map<String, List<String>> out,
            Map<String, PlanNode> nodes,
            String stopJoinId) {
        return walkToJoin(forkId, targetId, out, nodes, stopJoinId, new HashSet<>());
    }

    private static boolean walkToJoin(
            String current,
            String targetId,
            Map<String, List<String>> out,
            Map<String, PlanNode> nodes,
            String stopJoinId,
            Set<String> visiting) {
        if (!StringUtils.hasText(current) || visiting.contains(current)) {
            return false;
        }
        if (current.equals(targetId)) {
            return true;
        }
        if (stopJoinId.equals(current)) {
            return false;
        }
        visiting.add(current);
        for (String next : out.getOrDefault(current, List.of())) {
            PlanNode node = nodes.get(next);
            if (node != null && "join".equals(node.type()) && !stopJoinId.equals(next)) {
                continue;
            }
            if (walkToJoin(next, targetId, out, nodes, stopJoinId, visiting)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, List<String>> outAdj(PlanJson plan) {
        Map<String, List<String>> adj = new HashMap<>();
        for (PlanEdge edge : plan.edges()) {
            adj.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
        }
        return adj;
    }

    private static Map<String, List<String>> inAdj(PlanJson plan) {
        Map<String, List<String>> adj = new HashMap<>();
        for (PlanEdge edge : plan.edges()) {
            adj.computeIfAbsent(edge.to(), k -> new ArrayList<>()).add(edge.from());
        }
        return adj;
    }
}
