package com.sunshine.orchestrator.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从 chat_message.steps JSON 提取 QueryRewrite 审计字段 */
public final class QueryRewriteAuditExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private QueryRewriteAuditExtractor() {
    }

    public record Summary(boolean rewriteApplied, long rewriteLatencyMs, List<Map<String, Object>> rewrites) {
        public static Summary empty() {
            return new Summary(false, 0L, List.of());
        }
    }

    public static Summary fromStepsJson(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return Summary.empty();
        }
        try {
            List<Map<String, Object>> steps = MAPPER.readValue(stepsJson, new TypeReference<>() {
            });
            List<Map<String, Object>> rewrites = new ArrayList<>();
            long totalLatency = 0L;
            boolean applied = false;
            for (Map<String, Object> step : steps) {
                Object metadataObj = step.get("metadata");
                if (!(metadataObj instanceof Map<?, ?> rawMeta)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) rawMeta;
                Boolean stepApplied = asBoolean(metadata.get("rewriteApplied"));
                if (stepApplied == null || !stepApplied) {
                    continue;
                }
                applied = true;
                totalLatency += asLong(metadata.get("rewriteLatencyMs"));
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("scenario", metadata.get("rewriteScenario"));
                row.put("from", metadata.get("rewriteFrom"));
                row.put("to", metadata.get("rewriteTo"));
                row.put("latencyMs", metadata.get("rewriteLatencyMs"));
                rewrites.add(row);
            }
            return new Summary(applied, totalLatency, rewrites);
        } catch (Exception ignored) {
            return Summary.empty();
        }
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return null;
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return 0L;
    }
}
