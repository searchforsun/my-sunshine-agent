package com.sunshine.orchestrator.hitl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.util.StringUtils;

/** HITL 确认框参数摘要格式化 */
final class HitlParamSupport {

    private HitlParamSupport() {
    }

    static Map<String, String> parseParamsSummary(String summary) {
        if (!StringUtils.hasText(summary)) {
            return Map.of();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String part : summary.split(",\\s*")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                map.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return map;
    }

    static String summarizeParams(Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        return params.entrySet().stream()
                .map(e -> e.getKey() + "=" + truncateParamValue(e.getValue()))
                .collect(Collectors.joining(", "));
    }

    /** HITL 确认框参数摘要：单行 key=value，过长截断 */
    private static String truncateParamValue(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.strip().replace('\n', ' ');
        int maxLen = 120;
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "…";
    }
}
