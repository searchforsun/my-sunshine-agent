package com.sunshine.orchestrator.processing;

/** 标准步骤标题（配置见 Nacos agent.timeline） */
public final class TimelineStepLabels {

    private static volatile TimelineStepLabelService service;

    private TimelineStepLabels() {
    }

    public static void bind(TimelineStepLabelService labelService) {
        service = labelService;
    }

    public static String label(String stepId) {
        return requireService().stepLabel(stepId);
    }

    private static TimelineStepLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("TimelineStepLabelService 未 bind");
        }
        return service;
    }
}
