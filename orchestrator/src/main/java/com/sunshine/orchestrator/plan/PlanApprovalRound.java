package com.sunshine.orchestrator.plan;

/** 用户确认执行计划的一轮交互（持久化于 execution_plan.approval_rounds） */
public record PlanApprovalRound(
        int roundNo,
        String status,
        String userHint,
        String chainSummary,
        Long createdAt,
        Long resolvedAt) {

    public static final String STATUS_AWAITING = "awaiting";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REGENERATED = "regenerated";
    public static final String STATUS_TIMED_OUT = "timed_out";

    public static PlanApprovalRound awaiting(int roundNo, String chainSummary, long createdAt) {
        return new PlanApprovalRound(roundNo, STATUS_AWAITING, null, chainSummary, createdAt, null);
    }

    public PlanApprovalRound resolve(String status, String userHint, long resolvedAt) {
        return new PlanApprovalRound(roundNo, status, userHint, chainSummary, createdAt, resolvedAt);
    }
}
