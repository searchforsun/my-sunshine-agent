package com.sunshine.orchestrator.processing;

/** 标准步骤标题（配置见 Nacos agent.timeline） */
public final class TimelineStepLabels {

    private static volatile IntentLabelService service;

    private TimelineStepLabels() {
    }

    public static void bind(IntentLabelService labelService) {
        service = labelService;
    }

    public static String label(String stepId) {
        return requireService().stepLabel(stepId);
    }

    private static IntentLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("IntentLabelService 未 bind");
        }
        return service;
    }
}
