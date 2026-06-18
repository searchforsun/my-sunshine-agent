package com.sunshine.orchestrator.processing;

/**
 * 时间线步骤 before / active 文案静态入口（配置见 Nacos agent.timeline）
 */
public final class TimelineLabels {

    private static volatile IntentLabelService service;

    private TimelineLabels() {
    }

    public static void bind(IntentLabelService labelService) {
        service = labelService;
    }

    public static String before(String stepId, String clippedQuery) {
        if (service != null) {
            return service.stepBefore(stepId, clippedQuery);
        }
        return fallbackBefore(stepId, clippedQuery);
    }

    public static String active(String stepId, String clippedQuery) {
        if (service != null) {
            return service.stepActive(stepId, clippedQuery);
        }
        return fallbackActive(stepId, clippedQuery);
    }

    private static String fallbackBefore(String stepId, String q) {
        if ("intent".equals(stepId)) {
            return "阅读" + q;
        }
        return StepLabels.beforeFor(stepId);
    }

    private static String fallbackActive(String stepId, String q) {
        if ("intent".equals(stepId)) {
            return "正在分析" + q + "，匹配最佳处理方式";
        }
        if ("rag".equals(stepId)) {
            return "正在匹配与" + q + "最相关的文档片段";
        }
        return StepLabels.activeFor(stepId);
    }
}
