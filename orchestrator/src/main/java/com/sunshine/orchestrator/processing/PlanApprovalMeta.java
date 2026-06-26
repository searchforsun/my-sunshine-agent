package com.sunshine.orchestrator.processing;

import java.util.List;
import java.util.Map;

/** Plan 用户确认 — 挂在 plan 步骤 metadata */
public record PlanApprovalMeta(
        String status,
        String token,
        Long expiresAt,
        List<PlanApprovalRoundMeta> rounds,
        /** 待确认阶段 DAG 预览（nodes/edges），前端无需等 execution-plans API */
        Map<String, Object> planGraph) {

    public static final String STATUS_AWAITING = "awaiting";
    public static final String STATUS_APPROVED = "approved";

    public static PlanApprovalMeta awaiting(
            String token,
            long expiresAt,
            List<PlanApprovalRoundMeta> rounds,
            Map<String, Object> planGraph) {
        return new PlanApprovalMeta(STATUS_AWAITING, token, expiresAt, rounds, planGraph);
    }

    public static PlanApprovalMeta approved(List<PlanApprovalRoundMeta> rounds, Map<String, Object> planGraph) {
        return new PlanApprovalMeta(STATUS_APPROVED, null, null, rounds, planGraph);
    }
}
