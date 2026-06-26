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

    public String plannerAttemptsToJson(List<PlannerAttempt> attempts) {
        try {
            return objectMapper.writeValueAsString(attempts);
        } catch (Exception e) {
            throw new PlanParseException("planner_attempts 序列化失败: " + e.getMessage());
        }
    }

    public List<PlannerAttempt> plannerAttemptsFromJson(String json) {
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

    public String checkpointToJson(WorkflowCheckpoint checkpoint) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "resumeNodeId", checkpoint.resumeNodeId(),
                    "wfCtxJson", checkpoint.wfCtxJson() != null ? checkpoint.wfCtxJson() : "{}"));
        } catch (Exception e) {
            throw new PlanParseException("pause_checkpoint 序列化失败: " + e.getMessage());
        }
    }

    public WorkflowCheckpoint checkpointFromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new PlanParseException("pause_checkpoint 为空");
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {
            });
            String resumeNodeId = String.valueOf(map.getOrDefault("resumeNodeId", ""));
            String wfCtxJson = String.valueOf(map.getOrDefault("wfCtxJson", "{}"));
            if (resumeNodeId.isBlank()) {
                throw new PlanParseException("pause_checkpoint 缺少 resumeNodeId");
            }
            return new WorkflowCheckpoint(resumeNodeId, wfCtxJson);
        } catch (PlanParseException e) {
            throw e;
        } catch (Exception e) {
            throw new PlanParseException("pause_checkpoint 解析失败: " + e.getMessage());
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
