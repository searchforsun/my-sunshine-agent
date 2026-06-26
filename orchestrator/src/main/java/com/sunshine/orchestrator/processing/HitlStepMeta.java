package com.sunshine.orchestrator.processing;

/** 写工具 HITL — 挂在工具步骤 metadata，刷新/重连后可恢复确认按钮 */
public record HitlStepMeta(
        String status,
        String token,
        String toolDisplayName,
        String paramsSummary,
        Long expiresAt) {

    public static final String STATUS_AWAITING = "awaiting";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_DENIED = "denied";

    public static HitlStepMeta awaiting(
            String token, String toolDisplayName, String paramsSummary, long expiresAt) {
        return new HitlStepMeta(STATUS_AWAITING, token, toolDisplayName, paramsSummary, expiresAt);
    }

    public static HitlStepMeta resolved(String status, HitlStepMeta prior) {
        if (prior == null) {
            return null;
        }
        return new HitlStepMeta(status, null, prior.toolDisplayName(), prior.paramsSummary(), prior.expiresAt());
    }
}
