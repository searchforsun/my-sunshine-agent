package com.sunshine.orchestrator.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.execution.InputBinding;
import com.sunshine.orchestrator.execution.VarType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 解析 Planner LLM 输出为 PlanJson */
@Slf4j
@Component
public class PlanJsonParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlanJson parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PlanParseException("Planner 输出为空");
        }
        String trimmed = extractJsonObject(stripMarkdownFence(raw.trim()));
        if (!trimmed.startsWith("{")) {
            throw new PlanParseException("Planner 输出非 JSON");
        }
        try {
            return readPlan(trimmed);
        } catch (Exception e) {
            log.warn("[PlanJsonParser] parse failed: {}", e.getMessage());
            throw new PlanParseException("Plan JSON 解析失败: " + e.getMessage());
        }
    }

    private PlanJson readPlan(String trimmed) throws Exception {
        JsonNode root = objectMapper.readTree(trimmed);
        String planId = text(root, "planId");
        String reason = text(root, "reason");
        List<PlanNode> nodes = parseNodes(root.get("nodes"));
        List<PlanEdge> edges = parseEdges(root.get("edges"));
        Map<String, PlanLayoutPoint> layout = parseLayout(root.get("layout"));
        if (nodes.isEmpty()) {
            throw new PlanParseException("Plan 缺少 nodes");
        }
        return new PlanJson(planId, reason, nodes, edges, layout);
    }

    private static List<PlanNode> parseNodes(JsonNode nodesNode) {
        List<PlanNode> nodes = new ArrayList<>();
        if (nodesNode == null || !nodesNode.isArray()) {
            return nodes;
        }
        for (JsonNode node : nodesNode) {
            String id = text(node, "id");
            String type = text(node, "type");
            if (id == null || type == null) {
                continue;
            }
            Map<String, Object> params = parseParams(node.get("params"));
            if (params.isEmpty()) {
                params = parseParams(node.get("config"));
            }
            List<InputBinding> inputs = parseInputs(node.get("inputs"));
            String displayName = text(node, "displayName");
            String parentId = text(node, "parentId");
            nodes.add(new PlanNode(id, type, params, inputs, displayName, parentId));
        }
        return List.copyOf(nodes);
    }

    private static List<InputBinding> parseInputs(JsonNode inputsNode) {
        List<InputBinding> inputs = new ArrayList<>();
        if (inputsNode == null || !inputsNode.isArray()) {
            return inputs;
        }
        for (JsonNode item : inputsNode) {
            String name = text(item, "name");
            String source = text(item, "source");
            if (name == null || name.isBlank()) {
                continue;
            }
            String typeStr = text(item, "type");
            VarType type = parseVarType(typeStr);
            boolean required = item.has("required") && item.get("required").asBoolean(false);
            inputs.add(new InputBinding(name, source != null ? source : "", type, required));
        }
        return List.copyOf(inputs);
    }

    private static VarType parseVarType(String typeStr) {
        if (typeStr == null || typeStr.isBlank()) {
            return VarType.STRING;
        }
        return switch (typeStr.toLowerCase()) {
            case "number" -> VarType.NUMBER;
            case "boolean" -> VarType.BOOLEAN;
            case "object" -> VarType.OBJECT;
            case "array" -> VarType.ARRAY;
            default -> VarType.STRING;
        };
    }

    private static Map<String, PlanLayoutPoint> parseLayout(JsonNode layoutNode) {
        Map<String, PlanLayoutPoint> layout = new LinkedHashMap<>();
        if (layoutNode == null || !layoutNode.isObject()) {
            return layout;
        }
        Iterator<Map.Entry<String, JsonNode>> it = layoutNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode pos = e.getValue();
            if (pos == null || !pos.isObject()) {
                continue;
            }
            JsonNode xNode = pos.get("x");
            JsonNode yNode = pos.get("y");
            if (xNode == null || yNode == null || !xNode.isNumber() || !yNode.isNumber()) {
                continue;
            }
            Double width = null;
            Double height = null;
            JsonNode wNode = pos.get("width");
            JsonNode hNode = pos.get("height");
            if (wNode != null && wNode.isNumber() && wNode.asDouble() > 0) {
                width = wNode.asDouble();
            }
            if (hNode != null && hNode.isNumber() && hNode.asDouble() > 0) {
                height = hNode.asDouble();
            }
            layout.put(e.getKey(), new PlanLayoutPoint(xNode.asDouble(), yNode.asDouble(), width, height));
        }
        return layout;
    }

    private static List<PlanEdge> parseEdges(JsonNode edgesNode) {
        List<PlanEdge> edges = new ArrayList<>();
        if (edgesNode == null || !edgesNode.isArray()) {
            return edges;
        }
        for (JsonNode edge : edgesNode) {
            String from = text(edge, "from");
            String to = text(edge, "to");
            if (from == null || to == null) {
                continue;
            }
            boolean isDefault = edge.has("default") && edge.get("default").asBoolean(false);
            PlanEdgeConditionGroup condition = parseConditionGroup(edge.get("condition"));
            edges.add(new PlanEdge(from, to, condition, isDefault));
        }
        return List.copyOf(edges);
    }

    private static PlanEdgeConditionGroup parseConditionGroup(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode itemsNode = node.get("items");
        if (itemsNode == null || !itemsNode.isArray()) {
            return null;
        }
        String logic = text(node, "logic");
        List<PlanEdgeCondition> items = new ArrayList<>();
        for (JsonNode item : itemsNode) {
            PlanEdgeCondition c = parseSingleCondition(item);
            if (c != null) {
                items.add(c);
            }
        }
        if (items.isEmpty() && logic == null) {
            return null;
        }
        return new PlanEdgeConditionGroup(logic, items);
    }

    private static PlanEdgeCondition parseSingleCondition(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String left = text(node, "left");
        String op = text(node, "op");
        String right = text(node, "right");
        if ((left == null || left.isBlank()) && (op == null || op.isBlank()) && (right == null || right.isBlank())) {
            return null;
        }
        return new PlanEdgeCondition(
                left != null ? left : "",
                op != null ? op : "",
                right != null ? right : "");
    }

    private static Map<String, Object> parseParams(JsonNode paramsNode) {
        Map<String, Object> params = new HashMap<>();
        if (paramsNode == null || !paramsNode.isObject()) {
            return params;
        }
        Iterator<Map.Entry<String, JsonNode>> it = paramsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v == null || v.isNull()) {
                params.put(e.getKey(), null);
            } else if (v.isObject() || v.isArray()) {
                params.put(e.getKey(), v);
            } else if (v.isNumber()) {
                params.put(e.getKey(), v.numberValue());
            } else if (v.isBoolean()) {
                params.put(e.getKey(), v.booleanValue());
            } else {
                params.put(e.getKey(), v.asText(""));
            }
        }
        return params;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() ? v.asText() : null;
    }

    private static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1).trim();
        }
        return raw;
    }

    private static String stripMarkdownFence(String raw) {
        if (!raw.startsWith("```")) {
            return raw;
        }
        int firstNl = raw.indexOf('\n');
        if (firstNl < 0) {
            return raw;
        }
        int end = raw.lastIndexOf("```");
        if (end <= firstNl) {
            return raw.substring(firstNl + 1).trim();
        }
        return raw.substring(firstNl + 1, end).trim();
    }
}
