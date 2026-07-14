package com.sunshine.orchestrator.plan;

import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Plan DAG → 执行调度（串行 + 并行 fan-out/join + 排他条件分支） */
public final class PlanExecutionSchedule {

    private PlanExecutionSchedule() {
    }

    public sealed interface Step permits Single, Parallel, Exclusive {
    }

    public record Single(String nodeId) implements Step {
    }

    public record Parallel(List<String> branchNodeIds, String joinNodeId) implements Step {
        public Parallel {
            branchNodeIds = List.copyOf(branchNodeIds);
        }
    }

    /** 排他网关：运行时按边条件选中一臂并执行 pathNodeIds */
    public record ExclusiveArm(
            String targetNodeId,
            PlanEdgeCondition condition,
            boolean isDefault,
            List<String> pathNodeIds) {
        public ExclusiveArm {
            pathNodeIds = pathNodeIds != null ? List.copyOf(pathNodeIds) : List.of();
        }
    }

    public record Exclusive(String gatewayNodeId, List<ExclusiveArm> arms) implements Step {
        public Exclusive {
            arms = List.copyOf(arms);
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
            String forkId = findFanOutFork(preds, in, nodes);
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

    /** 排他网关边条件校验；通过返回 null */
    public static String validateExclusiveTopology(PlanJson plan) {
        if (plan == null || plan.nodes().isEmpty()) {
            return "nodes 为空";
        }
        Map<String, PlanNode> nodes = plan.nodesById();
        Map<String, List<PlanEdge>> outEdges = outEdges(plan);
        for (PlanNode node : plan.nodes()) {
            if (!"exclusive-gateway".equals(node.type())) {
                continue;
            }
            List<PlanEdge> edges = outEdges.getOrDefault(node.id(), List.of());
            if (edges.size() < 2) {
                return "exclusive-gateway 节点 " + node.id() + " 出度须 ≥ 2";
            }
            long defaults = edges.stream().filter(PlanEdge::isDefault).count();
            if (defaults != 1) {
                return "exclusive-gateway 节点 " + node.id() + " 须恰好 1 条 default 出边";
            }
            for (PlanEdge edge : edges) {
                if (edge.isDefault()) {
                    continue;
                }
                if (!edge.hasCondition()) {
                    return "exclusive-gateway 出边 " + edge.from() + "→" + edge.to() + " 须配置 condition 或标记 default";
                }
            }
        }
        for (PlanEdge edge : plan.edges()) {
            if (!edge.hasCondition() && !edge.isDefault()) {
                continue;
            }
            PlanNode from = nodes.get(edge.from());
            if (from == null || !"exclusive-gateway".equals(from.type())) {
                return "边 " + edge.from() + "→" + edge.to() + " 的 condition/default 仅允许 exclusive-gateway 出边";
            }
        }
        return null;
    }

    /** 构建执行调度；无 join/exclusive 时退化为线性 Single 链 */
    public static List<Step> build(PlanJson plan) {
        if (plan == null || plan.nodes().isEmpty()) {
            return List.of();
        }
        boolean hasJoin = plan.nodes().stream().anyMatch(n -> "join".equals(n.type()));
        boolean hasExclusive = plan.nodes().stream().anyMatch(n -> "exclusive-gateway".equals(n.type()));
        if (!hasJoin && !hasExclusive) {
            return PlanLinearizer.linearOrder(plan).stream()
                    .filter(id -> !"start".equals(id))
                    .<Step>map(Single::new)
                    .toList();
        }
        Map<String, PlanNode> nodes = plan.nodesById();
        Map<String, List<String>> out = outAdj(plan);
        Map<String, List<PlanEdge>> outEdges = outEdges(plan);
        List<Step> steps = new ArrayList<>();
        String cursor = "start";
        Set<String> visitedExclusive = new HashSet<>();
        while (true) {
            List<String> nexts = out.getOrDefault(cursor, List.of());
            if (nexts.isEmpty()) {
                break;
            }
            if (nexts.size() > 1) {
                PlanNode cursorNode = nodes.get(cursor);
                if (cursorNode != null && "exclusive-gateway".equals(cursorNode.type())) {
                    if (!visitedExclusive.add(cursor)) {
                        return List.of();
                    }
                    Exclusive exclusive = buildExclusive(cursor, outEdges.getOrDefault(cursor, List.of()), out, nodes);
                    if (exclusive == null) {
                        return List.of();
                    }
                    steps.add(exclusive);
                    String after = findExclusiveContinue(exclusive, out, nodes);
                    if (!StringUtils.hasText(after)) {
                        break;
                    }
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
            } else if (step instanceof Exclusive e) {
                order.add(e.gatewayNodeId());
                for (ExclusiveArm arm : e.arms()) {
                    order.addAll(arm.pathNodeIds());
                }
            }
        }
        return List.copyOf(order);
    }

    private static Exclusive buildExclusive(
            String gatewayId,
            List<PlanEdge> edges,
            Map<String, List<String>> out,
            Map<String, PlanNode> nodes) {
        if (edges.size() < 2) {
            return null;
        }
        List<String> heads = edges.stream().map(PlanEdge::to).toList();
        String converge = findCommonDescendant(heads, out, nodes);
        List<ExclusiveArm> arms = new ArrayList<>();
        for (PlanEdge edge : edges) {
            List<String> path = pathUntil(edge.to(), converge, out, nodes);
            arms.add(new ExclusiveArm(edge.to(), edge.condition(), edge.isDefault(), path));
        }
        return new Exclusive(gatewayId, arms);
    }

    /** Exclusive 执行完后继续的节点：汇合点；若臂直达 answer 则无后续 */
    private static String findExclusiveContinue(
            Exclusive exclusive,
            Map<String, List<String>> out,
            Map<String, PlanNode> nodes) {
        List<String> heads = exclusive.arms().stream().map(ExclusiveArm::targetNodeId).toList();
        String converge = findCommonDescendant(heads, out, nodes);
        if (StringUtils.hasText(converge)) {
            return converge;
        }
        return null;
    }

    private static List<String> pathUntil(
            String start,
            String stopExclusive,
            Map<String, List<String>> out,
            Map<String, PlanNode> nodes) {
        List<String> path = new ArrayList<>();
        String cur = start;
        Set<String> visiting = new HashSet<>();
        while (StringUtils.hasText(cur) && visiting.add(cur)) {
            if (stopExclusive != null && stopExclusive.equals(cur)) {
                break;
            }
            path.add(cur);
            PlanNode node = nodes.get(cur);
            if (node != null && "answer".equals(node.type())) {
                break;
            }
            List<String> nexts = out.getOrDefault(cur, List.of());
            if (nexts.size() != 1) {
                break;
            }
            String next = nexts.get(0);
            if (stopExclusive != null && stopExclusive.equals(next)) {
                break;
            }
            cur = next;
        }
        return path;
    }

    private static String findCommonDescendant(
            List<String> heads,
            Map<String, List<String>> out,
            Map<String, PlanNode> nodes) {
        if (heads == null || heads.isEmpty()) {
            return null;
        }
        Set<String> common = new LinkedHashSet<>(reachable(heads.get(0), out, nodes));
        for (int i = 1; i < heads.size(); i++) {
            common.retainAll(reachable(heads.get(i), out, nodes));
        }
        if (common.isEmpty()) {
            return null;
        }
        // 取拓扑上最靠前的公共点：对每个候选，若其它公共点都不可达自它，则更靠前
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String candidate : common) {
            int dist = minDistanceFromHeads(heads, candidate, out);
            if (dist >= 0 && dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    private static int minDistanceFromHeads(List<String> heads, String target, Map<String, List<String>> out) {
        int best = Integer.MAX_VALUE;
        for (String head : heads) {
            int d = distance(head, target, out);
            if (d >= 0 && d < best) {
                best = d;
            }
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private static int distance(String from, String to, Map<String, List<String>> out) {
        if (from.equals(to)) {
            return 0;
        }
        ArrayDeque<String> q = new ArrayDeque<>();
        Map<String, Integer> dist = new HashMap<>();
        q.add(from);
        dist.put(from, 0);
        while (!q.isEmpty()) {
            String cur = q.removeFirst();
            int d = dist.get(cur);
            for (String next : out.getOrDefault(cur, List.of())) {
                if (dist.containsKey(next)) {
                    continue;
                }
                if (next.equals(to)) {
                    return d + 1;
                }
                dist.put(next, d + 1);
                q.add(next);
            }
        }
        return -1;
    }

    private static Set<String> reachable(String start, Map<String, List<String>> out, Map<String, PlanNode> nodes) {
        Set<String> seen = new LinkedHashSet<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        q.add(start);
        while (!q.isEmpty()) {
            String cur = q.removeFirst();
            if (!seen.add(cur)) {
                continue;
            }
            for (String next : out.getOrDefault(cur, List.of())) {
                q.add(next);
            }
        }
        return seen;
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

    private static String findFanOutFork(
            List<String> joinPreds,
            Map<String, List<String>> inAdj,
            Map<String, PlanNode> nodes) {
        Set<String> candidates = new HashSet<>(inAdj.getOrDefault(joinPreds.get(0), List.of()));
        for (int i = 1; i < joinPreds.size(); i++) {
            candidates.retainAll(inAdj.getOrDefault(joinPreds.get(i), List.of()));
        }
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.stream().sorted()
                .filter(id -> {
                    PlanNode node = nodes.get(id);
                    return node != null && "parallel-gateway".equals(node.type());
                })
                .findFirst()
                .orElseGet(() -> candidates.stream().sorted().findFirst().orElse(null));
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

    private static Map<String, List<PlanEdge>> outEdges(PlanJson plan) {
        Map<String, List<PlanEdge>> adj = new HashMap<>();
        for (PlanEdge edge : plan.edges()) {
            adj.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
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
