package com.sunshine.orchestrator.processing;

/** Plan 用户确认时间线文案 */
public final class PlanApprovalLabels {

    private PlanApprovalLabels() {
    }

    public static String awaiting() {
        return "等待确认执行计划";
    }

    public static String approved() {
        return "已确认执行计划";
    }

    public static String regenerating() {
        return "正在根据修改意见重新规划…";
    }

    public static String timedOut() {
        return "确认超时，将改由自主智能体继续";
    }
}
