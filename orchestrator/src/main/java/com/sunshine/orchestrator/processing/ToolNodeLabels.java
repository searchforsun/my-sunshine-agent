package com.sunshine.orchestrator.processing;

/** tool-* / node-* 步骤时间线文案静态入口（配置见 Nacos agent.timeline.steps.tool / node） */
public final class ToolNodeLabels {

    private static volatile ToolNodeLabelService service;

    private ToolNodeLabels() {
    }

    public static void bind(ToolNodeLabelService labelService) {
        service = labelService;
    }

    public static String toolDisplayName(String stepId) {
        return requireService().toolDisplayName(stepId);
    }

    public static String toolLabel(String stepId) {
        return requireService().toolLabel(stepId);
    }

    public static String toolBefore(String stepId) {
        return requireService().toolBefore(stepId);
    }

    public static String toolActive(String stepId) {
        return requireService().toolActive(stepId);
    }

    public static String toolAfter(String stepId, String detail) {
        return requireService().toolAfter(stepId, detail);
    }

    public static String nodeBefore(String stepId, String clippedQuery, String displayNameOverride) {
        return requireService().nodeBefore(stepId, clippedQuery, displayNameOverride);
    }

    public static String nodeActive(String stepId, String displayNameOverride) {
        return requireService().nodeActive(stepId, displayNameOverride);
    }

    public static String nodeAfter(String stepId, String detail, String displayNameOverride) {
        return requireService().nodeAfter(stepId, detail, displayNameOverride);
    }

    private static ToolNodeLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("ToolNodeLabelService 未 bind");
        }
        return service;
    }
}
