package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.routing.ExecutionMode;

/** think / think-N 步骤标题（配置见 Nacos agent.timeline.steps.think） */
public final class ThinkStepLabels {

    private static volatile ThinkStepLabelService service;

    private ThinkStepLabels() {
    }

    public static void bind(ThinkStepLabelService labelService) {
        service = labelService;
    }

    public static String label(String stepId, ExecutionMode mode) {
        return requireService().thinkStepLabel(stepId, mode);
    }

    public static String before(String stepId, ExecutionMode mode, String clippedQuery, String toolDisplayName) {
        return requireService().thinkStepBefore(stepId, mode, clippedQuery, toolDisplayName);
    }

    public static String active(String stepId, ExecutionMode mode, String clippedQuery, String toolDisplayName) {
        return requireService().thinkStepActive(stepId, mode, clippedQuery, toolDisplayName);
    }

    public static String after(String stepId, ExecutionMode mode, String clippedQuery, String toolDisplayName) {
        return requireService().thinkStepAfter(stepId, mode, clippedQuery, toolDisplayName);
    }

    private static ThinkStepLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("ThinkStepLabelService 未 bind");
        }
        return service;
    }
}
