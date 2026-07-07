package com.sunshine.orchestrator.processing;

/** HITL 时间线文案静态入口（配置见 Nacos agent.timeline.hitl） */
public final class HitlLabels {

    private static volatile HitlLabelService service;

    private HitlLabels() {
    }

    public static void bind(HitlLabelService labelService) {
        service = labelService;
    }

    public static String pending(String toolDisplayName) {
        return requireService().pending(toolDisplayName);
    }

    public static String awaiting() {
        return requireService().awaiting();
    }

    public static String approved(String toolDisplayName) {
        return requireService().approved(toolDisplayName);
    }

    public static String denied() {
        return requireService().denied();
    }

    public static String skippedAfter() {
        return requireService().skippedAfter();
    }

    private static HitlLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("HitlLabelService 未 bind");
        }
        return service;
    }
}
