package com.sunshine.orchestrator.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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
        if (nodes.isEmpty()) {
            throw new PlanParseException("Plan 缺少 nodes");
        }
        return new PlanJson(planId, reason, nodes, edges);
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
            Map<String, String> params = parseParams(node.get("params"));
            if (params.isEmpty()) {
                params = parseParams(node.get("config"));
            }
            String displayName = text(node, "displayName");
            nodes.add(new PlanNode(id, type, params, displayName));
        }
        return List.copyOf(nodes);
    }

    private static List<PlanEdge> parseEdges(JsonNode edgesNode) {
        List<PlanEdge> edges = new ArrayList<>();
        if (edgesNode == null || !edgesNode.isArray()) {
            return edges;
        }
        for (JsonNode edge : edgesNode) {
            String from = text(edge, "from");
            String to = text(edge, "to");
            if (from != null && to != null) {
                edges.add(new PlanEdge(from, to));
            }
        }
        return List.copyOf(edges);
    }

    private static Map<String, String> parseParams(JsonNode paramsNode) {
        Map<String, String> params = new HashMap<>();
        if (paramsNode == null || !paramsNode.isObject()) {
            return params;
        }
        Iterator<Map.Entry<String, JsonNode>> it = paramsNode.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            params.put(e.getKey(), e.getValue().asText(""));
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
