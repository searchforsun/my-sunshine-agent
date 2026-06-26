package com.sunshine.bff.model;

/** Workflow 节点失败：用户重试或终止 */
public record ConfirmWorkflowNodeRecoveryRequest(String token, String action) {
}
