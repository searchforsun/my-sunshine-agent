package com.sunshine.orchestrator.processing;

/** Workflow 节点失败 — 用户选择重试/终止 */
public record NodeRecoveryMeta(
        String status,
        String token,
        String errorMessage,
        Long expiresAt) {

    public static final String STATUS_AWAITING = "awaiting";
    public static final String STATUS_RETRY = "retry";
    public static final String STATUS_SKIPPED = "skipped";
    public static final String STATUS_TERMINATED = "terminated";

    public static NodeRecoveryMeta awaiting(String token, String errorMessage, long expiresAt) {
        return new NodeRecoveryMeta(STATUS_AWAITING, token, errorMessage, expiresAt);
    }

    public static NodeRecoveryMeta resolved(String status, NodeRecoveryMeta prior) {
        if (prior == null) {
            return null;
        }
        return new NodeRecoveryMeta(status, null, prior.errorMessage(), prior.expiresAt());
    }
}
