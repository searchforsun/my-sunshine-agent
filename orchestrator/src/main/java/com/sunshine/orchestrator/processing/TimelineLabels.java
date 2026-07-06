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
        return requireService().stepBefore(stepId, clippedQuery);
    }

    public static String active(String stepId, String clippedQuery) {
        return requireService().stepActive(stepId, clippedQuery);
    }

    private static IntentLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("IntentLabelService 未 bind");
        }
        return service;
    }
}
