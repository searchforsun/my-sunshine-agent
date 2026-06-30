package com.sunshine.orchestrator.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 解析 Intent LLM 输出的 JSON；失败或非 JSON 裸字符串时 fallback
 */
@Slf4j
@Component
public class ExecutionPlanParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExecutionPlan parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ExecutionPlan.reactFallback("empty intent response");
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("{")) {
            return parseStoredIntent(trimmed);
        }
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            ExecutionMode mode = ExecutionMode.from(text(node, "mode"));
            String workflowId = text(node, "workflowId");
            String reason = text(node, "reason");
            Map<String, String> params = parseParams(node.get("params"));
            return new ExecutionPlan(mode, workflowId, params, reason);
        } catch (Exception e) {
            log.warn("[ExecutionPlanParser] parse failed: {}", e.getMessage());
            return ExecutionPlan.reactFallback("parse error");
        }
    }

    /** 解析 DB 中已存的 intentLabel（simple-llm / react / plan-workflow / workflow:{id}） */
    public ExecutionPlan parseStoredIntent(String stored) {
        if (stored.startsWith("workflow:")) {
            String workflowId = stored.substring("workflow:".length());
            return new ExecutionPlan(ExecutionMode.WORKFLOW, workflowId, Map.of(), "stored");
        }
        if ("simple-llm".equalsIgnoreCase(stored)) {
            return new ExecutionPlan(ExecutionMode.SIMPLE_LLM, null, Map.of(), "stored");
        }
        if ("react".equalsIgnoreCase(stored)) {
            return ExecutionPlan.reactFallback("stored");
        }
        if ("plan-workflow".equalsIgnoreCase(stored)) {
            return new ExecutionPlan(ExecutionMode.PLAN_WORKFLOW, null, Map.of(), "stored");
        }
        return ExecutionPlan.reactFallback("unknown stored intent: " + stored);
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
}
