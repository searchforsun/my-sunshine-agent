package com.sunshine.orchestrator.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PlanJson 与 execution_trace 序列化 */
@Component
@RequiredArgsConstructor
public class PlanJsonCodec {

    private final ObjectMapper objectMapper;

    public String toJson(PlanJson plan) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("planId", plan.planId());
            root.put("reason", plan.reason());
            root.put("nodes", plan.nodes().stream().map(this::nodeMap).toList());
            root.put("edges", plan.edges().stream()
                    .map(e -> Map.<String, String>of("from", e.from(), "to", e.to()))
                    .toList());
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new PlanParseException("Plan JSON 序列化失败: " + e.getMessage());
        }
    }

    public String traceToJson(List<PlanNodeTrace> traces) {
        try {
            return objectMapper.writeValueAsString(traces);
        } catch (Exception e) {
            throw new PlanParseException("execution_trace 序列化失败: " + e.getMessage());
        }
    }

    public List<PlanNodeTrace> traceFromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    public Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> nodeMap(PlanNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", node.id());
        map.put("type", node.type());
        if (node.displayName() != null) {
            map.put("displayName", node.displayName());
        }
        if (!node.params().isEmpty()) {
            map.put("params", node.params());
        }
        return map;
    }
}
