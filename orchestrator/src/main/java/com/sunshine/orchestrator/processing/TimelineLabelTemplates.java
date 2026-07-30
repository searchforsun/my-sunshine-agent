package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.routing.ExecutionMode;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/** 时间线文案占位符替换 — think / step / intent 共用 */
final class TimelineLabelTemplates {

    private TimelineLabelTemplates() {
    }

    static String modeConfigKey(ExecutionMode mode) {
        return switch (mode != null ? mode : ExecutionMode.REACT) {
            case WORKFLOW -> "workflow";
            case PLAN_WORKFLOW -> "plan-workflow";
            default -> "react";
        };
    }

    static String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.strip() : fallback;
    }

    static String coalesce(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    static String bracketTool(String displayName) {
        if (!StringUtils.hasText(displayName)) {
            return "";
        }
        return "「" + displayName.strip() + "」";
    }

    static Map<String, String> vars(String query, String detail, String workflowId, String displayName) {
        Map<String, String> map = new HashMap<>();
        map.put("query", query != null ? query : "");
        map.put("detail", detail != null ? detail : "");
        map.put("workflowId", workflowId != null ? workflowId : "");
        map.put("displayName", displayName != null ? displayName : "");
        return map;
    }

    static Map<String, String> thinkVars(String clippedQuery, String toolDisplayName) {
        Map<String, String> map = new HashMap<>();
        map.put("query", clippedQuery != null ? clippedQuery : "");
        map.put("toolDisplayName", bracketTool(toolDisplayName));
        return map;
    }

    static Map<String, String> taskVars(String activeTask, String taskProgress) {
        Map<String, String> map = new HashMap<>();
        map.put("activeTask", activeTask != null ? activeTask : "");
        map.put("taskProgress", taskProgress != null ? taskProgress : "");
        return map;
    }

    static String applyTemplate(String template, Map<String, String> vars) {
        if (!StringUtils.hasText(template)) {
            return "";
        }
        String result = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue());
        }
        return result;
    }
}
