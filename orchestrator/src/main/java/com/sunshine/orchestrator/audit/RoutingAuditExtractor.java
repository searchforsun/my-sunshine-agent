package com.sunshine.orchestrator.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从 chat_message.steps JSON 提取 intent 路由审计字段（含 user:forced-*） */
public final class RoutingAuditExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RoutingAuditExtractor() {
    }

    public record Summary(
            String routingReason,
            boolean userForced,
            String skillId,
            String plannerMode) {

        public static Summary empty() {
            return new Summary(null, false, null, null);
        }
    }

    public static Summary fromStepsJson(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return Summary.empty();
        }
        try {
            List<Map<String, Object>> steps = MAPPER.readValue(stepsJson, new TypeReference<>() {
            });
            for (Map<String, Object> step : steps) {
                if (!"intent".equals(step.get("id"))) {
                    continue;
                }
                Object metadataObj = step.get("metadata");
                if (!(metadataObj instanceof Map<?, ?> rawMeta)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) rawMeta;
                String routingReason = asString(metadata.get("routingReason"));
                boolean userForced = routingReason != null && routingReason.startsWith("user:forced");
                String skillId = asString(metadata.get("skillId"));
                String plannerMode = asString(metadata.get("plannerMode"));
                return new Summary(routingReason, userForced, skillId, plannerMode);
            }
            return Summary.empty();
        } catch (Exception ignored) {
            return Summary.empty();
        }
    }

    public static Map<String, Object> toPayloadMap(Summary summary) {
        if (summary.routingReason() == null && !summary.userForced()) {
            return Map.of();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        if (summary.routingReason() != null) {
            map.put("routingReason", summary.routingReason());
        }
        map.put("userForced", summary.userForced());
        if (summary.skillId() != null) {
            map.put("skillId", summary.skillId());
        }
        if (summary.plannerMode() != null) {
            map.put("plannerMode", summary.plannerMode());
        }
        return map;
    }

    private static String asString(Object value) {
        if (value instanceof String s && !s.isBlank()) {
            return s.strip();
        }
        return null;
    }
}
