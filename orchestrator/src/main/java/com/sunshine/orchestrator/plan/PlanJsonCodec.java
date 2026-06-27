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

    public String approvalRoundsToJson(List<PlanApprovalRound> rounds) {
        try {
            return objectMapper.writeValueAsString(rounds);
        } catch (Exception e) {
            throw new PlanParseException("approval_rounds 序列化失败: " + e.getMessage());
        }
    }

    public List<PlanApprovalRound> approvalRoundsFromJson(String json) {
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

    /** Plan 确认态 SSE 内嵌 DAG 预览 */
    public Map<String, Object> toGraphMap(PlanJson plan) {
        if (plan == null || plan.nodes() == null || plan.nodes().isEmpty()) {
            return Map.of();
        }
        return parseJsonMap(toJson(plan));
    }

    public String checkpointToJson(WorkflowCheckpoint checkpoint) {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("resumeNodeId", checkpoint.resumeNodeId() != null ? checkpoint.resumeNodeId() : "");
            root.put("wfCtxJson", checkpoint.wfCtxJson() != null ? checkpoint.wfCtxJson() : "{}");
            PausePhase phase = checkpoint.pausePhase() != null ? checkpoint.pausePhase() : PausePhase.EXECUTING;
            root.put("pausePhase", phase.name());
            PendingInteraction pending = checkpoint.pendingInteraction();
            if (pending != null) {
                Map<String, Object> pi = new LinkedHashMap<>();
                pi.put("kind", pending.kind());
                pi.put("nodeId", pending.nodeId());
                if (pending.errorMessage() != null) {
                    pi.put("errorMessage", pending.errorMessage());
                }
                if (pending.hitlToolId() != null) {
                    pi.put("hitlToolId", pending.hitlToolId());
                }
                if (pending.hitlParamsSummary() != null) {
                    pi.put("hitlParamsSummary", pending.hitlParamsSummary());
                }
                if (pending.recoveryAttemptsJson() != null) {
                    pi.put("recoveryAttempts", objectMapper.readValue(
                            pending.recoveryAttemptsJson(), new TypeReference<>() {
                            }));
                }
                root.put("pendingInteraction", pi);
            }
            return objectMapper.writeValueAsString(root);
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
            PausePhase pausePhase = PausePhase.fromDb(String.valueOf(map.getOrDefault("pausePhase", "")));
            PendingInteraction pendingInteraction = parsePendingInteraction(map.get("pendingInteraction"));
            return new WorkflowCheckpoint(resumeNodeId, wfCtxJson, pausePhase, pendingInteraction);
        } catch (PlanParseException e) {
            throw e;
        } catch (Exception e) {
            throw new PlanParseException("pause_checkpoint 解析失败: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private PendingInteraction parsePendingInteraction(Object raw) throws Exception {
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) rawMap;
        String kind = String.valueOf(map.getOrDefault("kind", ""));
        String nodeId = String.valueOf(map.getOrDefault("nodeId", ""));
        if (kind.isBlank() || nodeId.isBlank()) {
            return null;
        }
        String errorMessage = map.get("errorMessage") != null ? String.valueOf(map.get("errorMessage")) : null;
        String hitlToolId = map.get("hitlToolId") != null ? String.valueOf(map.get("hitlToolId")) : null;
        String hitlParamsSummary = map.get("hitlParamsSummary") != null
                ? String.valueOf(map.get("hitlParamsSummary")) : null;
        String recoveryAttemptsJson = null;
        if (map.get("recoveryAttempts") != null) {
            recoveryAttemptsJson = objectMapper.writeValueAsString(map.get("recoveryAttempts"));
        }
        return new PendingInteraction(kind, nodeId, errorMessage, hitlToolId, hitlParamsSummary, recoveryAttemptsJson);
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
