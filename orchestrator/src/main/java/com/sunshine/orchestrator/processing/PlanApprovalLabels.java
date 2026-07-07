package com.sunshine.orchestrator.processing;

/** Plan 用户确认时间线文案静态入口（配置见 Nacos agent.timeline.plan-approval） */
public final class PlanApprovalLabels {

    private static volatile PlanApprovalLabelService service;

    private PlanApprovalLabels() {
    }

    public static void bind(PlanApprovalLabelService labelService) {
        service = labelService;
    }

    public static String awaiting() {
        return requireService().awaiting();
    }

    public static String approved() {
        return requireService().approved();
    }

    public static String regenerating() {
        return requireService().regenerating();
    }

    public static String timedOut() {
        return requireService().timedOut();
    }

    private static PlanApprovalLabelService requireService() {
        if (service == null) {
            throw new IllegalStateException("PlanApprovalLabelService 未 bind");
        }
        return service;
    }
}
