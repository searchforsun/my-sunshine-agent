package com.sunshine.orchestrator.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import com.sunshine.orchestrator.skill.SkillBindingOutcome;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 解析 Intent LLM 输出的 JSON；失败或非 JSON 裸字符串时 fallback。
 * 不采纳 planMode / executionMode 作为执行模式（由用户钉死）。
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
            // 仅读 mode（兼容旧 classifier）；忽略 planMode / executionMode，防 L3 改轨
            ExecutionMode mode = ExecutionMode.from(text(node, "mode"));
            String workflowId = text(node, "workflowId");
            String reason = text(node, "reason");
            Map<String, String> params = parseParams(node.get("params"));
            mergeSkillId(node, params);
            mergeSkillIds(node, params);
            mergeAgentIds(node, params);
            return new ExecutionPlan(mode, workflowId, params, reason);
        } catch (Exception e) {
            log.warn("[ExecutionPlanParser] parse failed: {}", e.getMessage());
            return ExecutionPlan.reactFallback("parse error");
        }
    }

    public ExecutionPlan parseStoredIntent(String stored) {
        if (stored.startsWith("workflow:")) {
            String workflowId = stored.substring("workflow:".length());
            return new ExecutionPlan(ExecutionMode.WORKFLOW, workflowId, Map.of(), "stored");
        }
        return switch (stored.toLowerCase().replace('_', '-')) {
            case "fast" -> new ExecutionPlan(ExecutionMode.FAST, null, Map.of(), "stored");
            case "pro" -> new ExecutionPlan(ExecutionMode.PRO, null, Map.of(), "stored");
            default -> ExecutionPlan.reactFallback("unknown stored intent: " + stored);
        };
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

    private static void mergeSkillId(JsonNode node, Map<String, String> params) {
        String skillId = text(node, "skillId");
        if (!StringUtils.hasText(skillId)) {
            skillId = params.get(SkillBindingOutcome.PARAM_SKILL);
        }
        if (StringUtils.hasText(skillId)) {
            params.put(SkillBindingOutcome.PARAM_SKILL, skillId.strip());
        }
    }

    private static void mergeSkillIds(JsonNode node, Map<String, String> params) {
        List<String> ids = stringList(node.get("skillIds"));
        if (ids.isEmpty()) {
            return;
        }
        params.put("skillIds", String.join(",", ids));
        if (!StringUtils.hasText(params.get(SkillBindingOutcome.PARAM_SKILL))) {
            params.put(SkillBindingOutcome.PARAM_SKILL, ids.get(0));
        }
    }

    private static void mergeAgentIds(JsonNode node, Map<String, String> params) {
        List<String> ids = stringList(node.get("agentIds"));
        if (ids.isEmpty()) {
            String scalar = text(node, "agentIds");
            if (StringUtils.hasText(scalar)) {
                params.put("agentIds", scalar.strip());
            }
            return;
        }
        params.put("agentIds", String.join(",", ids));
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> out = new ArrayList<>();
            for (JsonNode item : node) {
                if (item != null && item.isTextual() && StringUtils.hasText(item.asText())) {
                    out.add(item.asText().strip());
                }
            }
            return out;
        }
        return List.of();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && !v.isNull() && v.isTextual() ? v.asText() : null;
    }
}
