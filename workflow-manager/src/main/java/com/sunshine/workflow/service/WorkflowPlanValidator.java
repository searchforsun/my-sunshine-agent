package com.sunshine.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** PlanJson 结构 + DAG 拓扑 + 节点间数据流校验（Studio 发布 SSOT） */
@Component
public class WorkflowPlanValidator {

    private static final Set<String> STUDIO_TYPES = WorkflowNodeType.studioTypeIds();
    private static final Set<String> BUSINESS_TYPES = WorkflowNodeType.businessTypeIds();
    private static final Set<String> ROUTING_ONLY_TYPES = WorkflowNodeType.routingOnlyTypeIds();
    private static final Set<String> LOOP_BODY_TYPES = WorkflowNodeType.loopBodyTypeIds();
    private static final Set<String> OUTPUT_TYPES = WorkflowNodeType.outputTypeIds();
    private static final Set<String> ON_MAX_ITERATIONS = Set.of("fail_fast", "exit", "fallback_react");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_.-]+)}}");

    private final ObjectMapper objectMapper;

    public WorkflowPlanValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WorkflowPlanValidationResult validateDetailed(Map<String, Object> plan) {
        WorkflowPlanValidationResult result = new WorkflowPlanValidationResult();
        if (plan == null || plan.isEmpty()) {
            result.add("plan 为空");
            return result;
        }
        try {
            JsonNode root = objectMapper.valueToTree(plan);
            JsonNode nodes = root.get("nodes");
            if (nodes == null || !nodes.isArray() || nodes.isEmpty()) {
                result.add("nodes 为空");
                return result;
            }
            Map<String, JsonNode> nodeById = new HashMap<>();
            Map<String, String> types = new HashMap<>();
            int answerCount = 0;
            boolean hasStart = false;
            for (JsonNode node : nodes) {
                String id = text(node, "id");
                String type = text(node, "type");
                if (!StringUtils.hasText(id) || !StringUtils.hasText(type)) {
                    result.add("节点缺少 id 或 type");
                    continue;
                }
                if (nodeById.containsKey(id)) {
                    result.add("节点 id 重复: " + id);
                    continue;
                }
                nodeById.put(id, node);
                types.put(id, type);
                if (!STUDIO_TYPES.contains(type)) {
                    result.add("非法节点类型: " + type + "（节点 " + id + "）");
                }
                if ("start".equals(type)) {
                    hasStart = true;
                }
                if ("answer".equals(type)) {
                    answerCount++;
                }
                validateNodeParams(result, node, id, type);
                validateRetryParams(result, node, id, type);
            }
            if (!hasStart) {
                result.add("Plan 须包含 start 节点");
            }
            if (answerCount == 0) {
                result.add("Plan 须包含 answer 节点");
            } else if (answerCount > 1) {
                result.add("Plan 只能包含一个 answer 节点");
            }
            long businessCount = types.values().stream().filter(BUSINESS_TYPES::contains).count();
            if (businessCount == 0) {
                result.add("Plan 须包含至少一个业务节点（rag / tool / agent 等）");
            }
            JsonNode edges = root.get("edges");
            if (edges == null || !edges.isArray() || edges.isEmpty()) {
                result.add("edges 为空，无法构成 DAG");
                return result;
            }
            Map<String, List<String>> outgoing = new HashMap<>();
            Map<String, List<String>> incoming = new HashMap<>();
            for (JsonNode edge : edges) {
                String from = text(edge, "from");
                String to = text(edge, "to");
                if (!StringUtils.hasText(from) || !StringUtils.hasText(to)) {
                    result.add("edge 缺少 from/to");
                    continue;
                }
                if (!nodeById.containsKey(from)) {
                    result.add("边引用未知节点 from=" + from);
                }
                if (!nodeById.containsKey(to)) {
                    result.add("边引用未知节点 to=" + to);
                }
                outgoing.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
                incoming.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
            }
            validateReachability(result, nodeById, types, outgoing, incoming);
            String parallelErr = validateParallelTopology(types, outgoing, incoming);
            if (parallelErr != null) {
                result.add(parallelErr);
            }
            String exclusiveErr = validateExclusiveEdgeConditions(types, edges);
            if (exclusiveErr != null) {
                result.add(exclusiveErr);
            }
            String loopErr = validateLoopTopology(nodeById, types, edges);
            if (loopErr != null) {
                result.add(loopErr);
            }
            Map<String, Set<String>> ancestors = buildAncestors(types.keySet(), incoming);
            validateDataFlow(result, nodeById, types, ancestors);
            return result;
        } catch (Exception e) {
            result.add("Plan 解析失败: " + e.getMessage());
            return result;
        }
    }

    private static final Set<String> ON_FAILURE_VALUES = Set.of(
            "continue", "fail_fast", "skip", "fallback_react");

    private void validateRetryParams(
            WorkflowPlanValidationResult result,
            JsonNode node,
            String id,
            String type) {
        if ("start".equals(type)) {
            return;
        }
        String onFailure = paramText(node, "retry.onFailure");
        if (!StringUtils.hasText(onFailure)) {
            result.add("节点 " + id + " 缺少 retry.onFailure（执行策略必填）");
        } else if (!ON_FAILURE_VALUES.contains(onFailure.strip().toLowerCase())) {
            result.add("节点 " + id + " 的 retry.onFailure 非法: " + onFailure
                    + "（可选: continue / fail_fast / skip / fallback_react）");
        }
        String maxAttempts = paramText(node, "retry.maxAttempts");
        if (!StringUtils.hasText(maxAttempts)) {
            result.add("节点 " + id + " 缺少 retry.maxAttempts（执行策略必填）");
        } else {
            try {
                int n = Integer.parseInt(maxAttempts.strip());
                if (n < 1 || n > 10) {
                    result.add("节点 " + id + " 的 retry.maxAttempts 须在 1–10 之间");
                }
            } catch (NumberFormatException e) {
                result.add("节点 " + id + " 的 retry.maxAttempts 须为整数");
            }
        }
        String backoff = paramText(node, "retry.backoffMs");
        if (!StringUtils.hasText(backoff)) {
            result.add("节点 " + id + " 缺少 retry.backoffMs（执行策略必填）");
        } else {
            try {
                long ms = Long.parseLong(backoff.strip());
                if (ms < 100 || ms > 30_000) {
                    result.add("节点 " + id + " 的 retry.backoffMs 须在 100–30000 之间");
                }
            } catch (NumberFormatException e) {
                result.add("节点 " + id + " 的 retry.backoffMs 须为整数");
            }
        }
    }

    private static void validateNodeParams(
            WorkflowPlanValidationResult result,
            JsonNode node,
            String id,
            String type) {
        if ("tool".equals(type)) {
            String tool = paramText(node, "tool");
            if (!StringUtils.hasText(tool)) {
                result.add("tool 节点 " + id + " 缺少 params.tool（Catalog ID）");
            }
        }
        if ("agent".equals(type)) {
            if (!StringUtils.hasText(paramText(node, "query"))) {
                result.add("agent 节点 " + id + " 缺少 params.query");
            }
            if (!StringUtils.hasText(paramText(node, "context"))) {
                result.add("agent 节点 " + id + " 缺少 params.context（上游数据引用）");
            }
        }
        if ("rag".equals(type)) {
            if (!StringUtils.hasText(paramText(node, "query"))) {
                result.add("rag 节点 " + id + " 缺少 params.query");
            }
        }
        if ("answer".equals(type)) {
            if (!StringUtils.hasText(paramText(node, "prompt"))) {
                result.add("answer 节点缺少 params.prompt");
            }
        }
    }

    private static void validateReachability(
            WorkflowPlanValidationResult result,
            Map<String, JsonNode> nodeById,
            Map<String, String> types,
            Map<String, List<String>> outgoing,
            Map<String, List<String>> incoming) {
        if (!types.containsKey("start")) {
            return;
        }
        Set<String> bodyIds = new HashSet<>();
        for (Map.Entry<String, JsonNode> e : nodeById.entrySet()) {
            if (StringUtils.hasText(text(e.getValue(), "parentId"))) {
                bodyIds.add(e.getKey());
            }
        }
        Set<String> reachable = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add("start");
        reachable.add("start");
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : outgoing.getOrDefault(current, List.of())) {
                if (bodyIds.contains(next)) {
                    continue;
                }
                if (reachable.add(next)) {
                    queue.add(next);
                }
            }
        }
        // loop 可达 ⇒ 其框内 body 视为可达（外图不直接连 body）
        for (String bodyId : bodyIds) {
            String parentId = text(nodeById.get(bodyId), "parentId");
            if (reachable.contains(parentId)) {
                reachable.add(bodyId);
            }
        }
        for (String id : types.keySet()) {
            if ("start".equals(id)) {
                continue;
            }
            if (!reachable.contains(id)) {
                String label = types.get(id);
                result.add("节点 " + id + "（" + label + "）不可从 start 到达，请检查 edges");
            }
        }
        String answerId = types.entrySet().stream()
                .filter(e -> "answer".equals(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        if (answerId != null && !reachable.contains(answerId)) {
            result.add("answer 节点不可从 start 到达");
        }
        for (Map.Entry<String, String> entry : types.entrySet()) {
            String id = entry.getKey();
            if ("start".equals(id) || "answer".equals(id) || bodyIds.contains(id)) {
                continue;
            }
            if (outgoing.getOrDefault(id, List.of()).isEmpty()) {
                result.add("节点 " + id + "（" + entry.getValue() + "）无出边，数据无法流向 answer");
            }
        }
    }

    private static String validateParallelTopology(
            Map<String, String> types,
            Map<String, List<String>> outgoing,
            Map<String, List<String>> incoming) {
        List<String> joinIds = types.entrySet().stream()
                .filter(e -> "join".equals(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        if (joinIds.isEmpty()) {
            return null;
        }
        for (String joinId : joinIds) {
            List<String> preds = incoming.getOrDefault(joinId, List.of());
            if (preds.size() < 2) {
                return "join 节点 " + joinId + " 入度须 ≥ 2（并行分支汇入）";
            }
            List<String> afterJoin = outgoing.getOrDefault(joinId, List.of());
            if (afterJoin.size() != 1) {
                return "join 节点 " + joinId + " 出度须为 1";
            }
        }
        List<String> pgIds = types.entrySet().stream()
                .filter(e -> "parallel-gateway".equals(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        for (String pgId : pgIds) {
            List<String> succs = outgoing.getOrDefault(pgId, List.of());
            if (succs.size() < 2) {
                return "parallel-gateway 节点 " + pgId + " 出度须 ≥ 2";
            }
        }
        List<String> xgIds = types.entrySet().stream()
                .filter(e -> "exclusive-gateway".equals(e.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        for (String xgId : xgIds) {
            List<String> succs = outgoing.getOrDefault(xgId, List.of());
            if (succs.size() < 2) {
                return "exclusive-gateway 节点 " + xgId + " 出度须 ≥ 2";
            }
        }
        return null;
    }

    private static String validateExclusiveEdgeConditions(
            Map<String, String> types,
            JsonNode edges) {
        Map<String, Integer> defaultCount = new HashMap<>();
        Map<String, Integer> outCount = new HashMap<>();
        for (JsonNode edge : edges) {
            String from = text(edge, "from");
            String to = text(edge, "to");
            if (!StringUtils.hasText(from) || !StringUtils.hasText(to)) {
                continue;
            }
            boolean isDefault = edge.has("default") && edge.get("default").asBoolean(false);
            JsonNode condition = edge.get("condition");
            boolean hasCond = condition != null && condition.isObject()
                    && StringUtils.hasText(text(condition, "op"));
            if (!isDefault && !hasCond) {
                if ("exclusive-gateway".equals(types.get(from))) {
                    // 允许先画拓扑后补条件；发布前须完整。此处仅统计。
                }
            }
            if (isDefault || hasCond) {
                if (!"exclusive-gateway".equals(types.get(from))) {
                    return "边 " + from + "→" + to + " 的 condition/default 仅允许 exclusive-gateway 出边";
                }
            }
            if ("exclusive-gateway".equals(types.get(from))) {
                outCount.merge(from, 1, Integer::sum);
                if (isDefault) {
                    defaultCount.merge(from, 1, Integer::sum);
                } else if (!hasCompleteCondition(condition)) {
                    return "exclusive-gateway 出边 " + from + "→" + to + " 须配置 condition 或标记 default";
                }
            }
        }
        for (Map.Entry<String, Integer> e : outCount.entrySet()) {
            if (e.getValue() < 2) {
                continue;
            }
            int defaults = defaultCount.getOrDefault(e.getKey(), 0);
            if (defaults != 1) {
                return "exclusive-gateway 节点 " + e.getKey() + " 须恰好 1 条 default 出边";
            }
        }
        return null;
    }

    private static boolean hasCompleteCondition(JsonNode condition) {
        if (condition == null || !condition.isObject()) {
            return false;
        }
        String op = text(condition, "op");
        String left = text(condition, "left");
        if (!StringUtils.hasText(op) || !StringUtils.hasText(left)) {
            return false;
        }
        String normalized = op.strip().toLowerCase();
        if ("empty".equals(normalized) || "not_empty".equals(normalized)) {
            return true;
        }
        return "contains".equals(normalized) || "eq".equals(normalized);
    }

    private static String validateLoopTopology(
            Map<String, JsonNode> nodeById,
            Map<String, String> types,
            JsonNode edges) {
        Map<String, String> parentOf = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : nodeById.entrySet()) {
            String parentId = text(e.getValue(), "parentId");
            if (!StringUtils.hasText(parentId)) {
                continue;
            }
            parentOf.put(e.getKey(), parentId);
            String parentType = types.get(parentId);
            if (parentType == null) {
                return "节点 " + e.getKey() + " 的 parentId 不存在: " + parentId;
            }
            if (!"loop".equals(parentType)) {
                return "节点 " + e.getKey() + " 的 parentId 须指向 loop 容器";
            }
            String type = types.get(e.getKey());
            if (!LOOP_BODY_TYPES.contains(type)) {
                return "loop 框内节点 " + e.getKey() + " 类型须为 rag/tool/agent";
            }
            if ("loop".equals(type)) {
                return "禁止嵌套 loop: " + e.getKey();
            }
        }
        for (JsonNode edge : edges) {
            String from = text(edge, "from");
            String to = text(edge, "to");
            if (!StringUtils.hasText(from) || !StringUtils.hasText(to)) {
                continue;
            }
            String pf = parentOf.get(from);
            String pt = parentOf.get(to);
            boolean fromBody = StringUtils.hasText(pf);
            boolean toBody = StringUtils.hasText(pt);
            if (fromBody != toBody) {
                return "禁止跨框边 " + from + "→" + to;
            }
            if (fromBody && toBody && !pf.equals(pt)) {
                return "禁止跨不同 loop 的边 " + from + "→" + to;
            }
        }
        for (Map.Entry<String, String> e : types.entrySet()) {
            if (!"loop".equals(e.getValue())) {
                continue;
            }
            String loopId = e.getKey();
            if (parentOf.containsKey(loopId)) {
                return "禁止嵌套 loop: " + loopId;
            }
            List<String> bodyIds = parentOf.entrySet().stream()
                    .filter(p -> loopId.equals(p.getValue()))
                    .map(Map.Entry::getKey)
                    .toList();
            if (bodyIds.isEmpty()) {
                return "loop 节点 " + loopId + " 须包含至少一个 body 节点";
            }
            String bodyErr = validateLinearBodyChain(bodyIds, edges);
            if (bodyErr != null) {
                return bodyErr;
            }
            int outerOut = 0;
            int outerIn = 0;
            for (JsonNode edge : edges) {
                String from = text(edge, "from");
                String to = text(edge, "to");
                if (loopId.equals(from) && !parentOf.containsKey(to)) {
                    outerOut++;
                }
                if (loopId.equals(to) && !parentOf.containsKey(from)) {
                    outerIn++;
                }
            }
            if (outerOut != 1) {
                return "loop 节点 " + loopId + " 外图出度须为 1";
            }
            if (outerIn < 1) {
                return "loop 节点 " + loopId + " 外图入度须 ≥ 1";
            }
            String paramErr = validateLoopParams(nodeById.get(loopId), loopId);
            if (paramErr != null) {
                return paramErr;
            }
        }
        return null;
    }

    private static String validateLinearBodyChain(List<String> bodyIds, JsonNode edges) {
        Set<String> bodySet = new HashSet<>(bodyIds);
        Map<String, List<String>> out = new HashMap<>();
        Map<String, Integer> indeg = new HashMap<>();
        for (String id : bodyIds) {
            indeg.put(id, 0);
            out.put(id, new ArrayList<>());
        }
        for (JsonNode edge : edges) {
            String from = text(edge, "from");
            String to = text(edge, "to");
            if (!bodySet.contains(from) || !bodySet.contains(to)) {
                continue;
            }
            out.get(from).add(to);
            indeg.merge(to, 1, Integer::sum);
        }
        List<String> roots = bodyIds.stream().filter(id -> indeg.getOrDefault(id, 0) == 0).toList();
        if (roots.size() != 1) {
            return "loop body 须为单链（恰好一个入度为 0 的入口）";
        }
        String cur = roots.get(0);
        Set<String> seen = new HashSet<>();
        while (cur != null) {
            if (!seen.add(cur)) {
                return "loop body 存在环";
            }
            List<String> nexts = out.getOrDefault(cur, List.of());
            if (nexts.size() > 1) {
                return "loop body 须为单链（节点 " + cur + " 出度 > 1）";
            }
            cur = nexts.isEmpty() ? null : nexts.get(0);
        }
        if (seen.size() != bodyIds.size()) {
            return "loop body 须连通为单链";
        }
        return null;
    }

    private static String validateLoopParams(JsonNode node, String loopId) {
        if (node == null) {
            return "loop 节点 " + loopId + " 缺失";
        }
        String maxRaw = paramText(node, "maxIterations");
        if (!StringUtils.hasText(maxRaw)) {
            maxRaw = "3";
        }
        int max;
        try {
            max = Integer.parseInt(maxRaw.strip());
        } catch (NumberFormatException e) {
            return "loop 节点 " + loopId + " 的 maxIterations 须为整数";
        }
        if (max < 1 || max > 5) {
            return "loop 节点 " + loopId + " 的 maxIterations 须在 1–5";
        }
        String onMax = paramText(node, "onMaxIterations");
        if (!StringUtils.hasText(onMax)) {
            onMax = "fail_fast";
        }
        if (!ON_MAX_ITERATIONS.contains(onMax.strip().toLowerCase())) {
            return "loop 节点 " + loopId + " 的 onMaxIterations 非法: " + onMax;
        }
        JsonNode params = node.get("params");
        if (params == null || !params.isObject()) {
            return "loop 节点 " + loopId + " 缺失 params";
        }
        JsonNode conditionsNode = params.get("conditions");
        if (conditionsNode == null || !conditionsNode.isArray() || conditionsNode.isEmpty()) {
            return "loop 节点 " + loopId + " 须配置 conditions 数组（至少一条继续条件）";
        }
        String logic = params.has("conditionLogic") ? params.get("conditionLogic").asText("") : "";
        if (StringUtils.hasText(logic)
                && !"and".equals(logic.strip().toLowerCase())
                && !"or".equals(logic.strip().toLowerCase())) {
            return "loop 节点 " + loopId + " 的 conditionLogic 非法: " + logic;
        }
        for (JsonNode item : conditionsNode) {
            String op = item.has("op") ? item.get("op").asText("").strip().toLowerCase() : "";
            String left = item.has("left") ? item.get("left").asText("").strip() : "";
            if (!StringUtils.hasText(op) || !StringUtils.hasText(left)) {
                return "loop 节点 " + loopId + " conditions 项须配置 op 与 left";
            }
            String opErr = validateLoopConditionOp(loopId, op,
                    item.has("right") ? item.get("right").asText("") : "");
            if (opErr != null) {
                return opErr;
            }
        }
        return null;
    }

    private static String validateLoopConditionOp(String loopId, String op, String right) {
        if (!"empty".equals(op) && !"not_empty".equals(op)
                && !"contains".equals(op) && !"not_contains".equals(op)
                && !"eq".equals(op) && !"not_eq".equals(op)
                && !"gt".equals(op) && !"lt".equals(op)
                && !"gte".equals(op) && !"lte".equals(op)) {
            return "loop 节点 " + loopId + " 的 condition.op 非法: " + op;
        }
        if (("contains".equals(op) || "eq".equals(op) || "not_contains".equals(op)
                || "not_eq".equals(op) || "gt".equals(op) || "lt".equals(op)
                || "gte".equals(op) || "lte".equals(op))
                && !StringUtils.hasText(right.strip())) {
            return "loop 节点 " + loopId + " 的 condition.right 不能为空";
        }
        return null;
    }

    private static Map<String, Set<String>> buildAncestors(
            Set<String> nodeIds,
            Map<String, List<String>> incoming) {
        Map<String, Set<String>> ancestors = new HashMap<>();
        for (String nodeId : nodeIds) {
            Set<String> anc = new HashSet<>();
            Deque<String> queue = new ArrayDeque<>(incoming.getOrDefault(nodeId, List.of()));
            while (!queue.isEmpty()) {
                String pred = queue.poll();
                if (!anc.add(pred)) {
                    continue;
                }
                queue.addAll(incoming.getOrDefault(pred, List.of()));
            }
            ancestors.put(nodeId, anc);
        }
        return ancestors;
    }

    private static void validateDataFlow(
            WorkflowPlanValidationResult result,
            Map<String, JsonNode> nodeById,
            Map<String, String> types,
            Map<String, Set<String>> ancestors) {
        for (Map.Entry<String, JsonNode> entry : nodeById.entrySet()) {
            String consumerId = entry.getKey();
            String consumerType = types.get(consumerId);
            if ("start".equals(consumerType)) {
                continue;
            }
            Set<String> allowedUpstream = ancestors.getOrDefault(consumerId, Set.of());
            collectParamStrings(entry.getValue().get("params")).forEach(text ->
                    validatePlaceholders(result, text, consumerId, consumerType, types, allowedUpstream));
        }
    }

    private static void validatePlaceholders(
            WorkflowPlanValidationResult result,
            String text,
            String consumerId,
            String consumerType,
            Map<String, String> types,
            Set<String> allowedUpstream) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            String path = matcher.group(1);
            if (!StringUtils.hasText(path)) {
                continue;
            }
            if (isSpecialPath(path)) {
                continue;
            }
            int dot = path.indexOf('.');
            if (dot < 0) {
                result.add(dataFlowIssue(consumerId, consumerType, path, "占位符格式须为 nodeId.field"));
                continue;
            }
            String producerId = path.substring(0, dot);
            String field = path.substring(dot + 1);
            String producerType = types.get(producerId);
            if (producerType == null) {
                result.add(dataFlowIssue(consumerId, consumerType, path,
                        "引用了不存在的节点 " + producerId));
                continue;
            }
            if ("answer".equals(producerType)) {
                result.add(dataFlowIssue(consumerId, consumerType, path,
                        "answer 节点输出不应作为上游引用"));
                continue;
            }
            if (ROUTING_ONLY_TYPES.contains(producerType)) {
                result.add(dataFlowIssue(consumerId, consumerType, path,
                        "网关节点「" + producerId + "」无业务输出，请勿引用占位符"));
                continue;
            }
            if (!isValidField(producerType, field)) {
                result.add(dataFlowIssue(consumerId, consumerType, path,
                        "节点 " + producerId + "（" + producerType + "）不支持字段 ." + field
                                + "，可用: " + allowedFields(producerType)));
                continue;
            }
            if ("start".equals(producerId)) {
                continue;
            }
            if (!allowedUpstream.contains(producerId)) {
                result.add(dataFlowIssue(consumerId, consumerType, path,
                        "节点 " + producerId + " 不是 " + consumerId + " 的上游，数据无法流入"));
            }
        }
    }

    private static boolean isSpecialPath(String path) {
        if ("start.userQuery".equals(path)) {
            return true;
        }
        if ("plan.upstream".equals(path)) {
            return true;
        }
        return path.startsWith("plan.params.");
    }

    private static boolean isValidField(String producerType, String field) {
        if ("start".equals(producerType)) {
            return "userQuery".equals(field);
        }
        if ("agent".equals(producerType)) {
            return "output".equals(field) || "answer".equals(field);
        }
        if ("tool".equals(producerType)) {
            return "output".equals(field) || "summary".equals(field) || field.startsWith("parsed.");
        }
        if (OUTPUT_TYPES.contains(producerType)) {
            return "output".equals(field);
        }
        return false;
    }

    private static String allowedFields(String producerType) {
        if ("start".equals(producerType)) {
            return "userQuery";
        }
        if ("agent".equals(producerType)) {
            return "output, answer";
        }
        if ("tool".equals(producerType)) {
            return "output, summary, parsed.*";
        }
        return "（无）";
    }

    private static String dataFlowIssue(
            String consumerId,
            String consumerType,
            String path,
            String reason) {
        return "数据流: 节点 " + consumerId + "（" + consumerType + "）引用 {{"
                + path + "}} — " + reason;
    }

    private static List<String> collectParamStrings(JsonNode params) {
        List<String> values = new ArrayList<>();
        if (params == null || !params.isObject()) {
            return values;
        }
        Iterator<Map.Entry<String, JsonNode>> it = params.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> field = it.next();
            JsonNode value = field.getValue();
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isTextual()) {
                values.add(value.asText());
            } else if (value.isNumber() || value.isBoolean()) {
                values.add(value.asText());
            }
        }
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText().strip() : null;
    }

    private static String paramText(JsonNode node, String key) {
        JsonNode params = node.get("params");
        if (params == null || !params.isObject()) {
            return null;
        }
        JsonNode v = params.get(key);
        return v != null && !v.isNull() ? v.asText().strip() : null;
    }
}
