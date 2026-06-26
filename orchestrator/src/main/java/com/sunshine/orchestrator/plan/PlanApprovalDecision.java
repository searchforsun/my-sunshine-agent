package com.sunshine.orchestrator.plan;

/** 用户对 Planner 产物的确认结果 */
public record PlanApprovalDecision(boolean approved, String modificationHint) {

    public static PlanApprovalDecision approve() {
        return new PlanApprovalDecision(true, null);
    }

    public static PlanApprovalDecision regenerate(String hint) {
        return new PlanApprovalDecision(false, hint);
    }
}
