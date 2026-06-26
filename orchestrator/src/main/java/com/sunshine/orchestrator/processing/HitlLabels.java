package com.sunshine.orchestrator.processing;

/** HITL 时间线文案静态入口 */
public final class HitlLabels {

    private static volatile HitlLabelService service;

    private HitlLabels() {
    }

    public static void bind(HitlLabelService labelService) {
        service = labelService;
    }

    public static String pending(String toolDisplayName) {
        return service != null ? service.pending(toolDisplayName) : "将调用工具 " + toolDisplayName;
    }

    public static String awaiting() {
        return service != null ? service.awaiting() : "等待用户确认执行写操作";
    }

    public static String approved(String toolDisplayName) {
        return service != null ? service.approved(toolDisplayName) : "用户已确认，正在调用 " + toolDisplayName;
    }

    public static String denied() {
        return service != null ? service.denied() : "用户取消调用";
    }

    public static String skippedAfter() {
        return service != null ? service.skippedAfter() : "用户取消调用，已跳过";
    }
}
