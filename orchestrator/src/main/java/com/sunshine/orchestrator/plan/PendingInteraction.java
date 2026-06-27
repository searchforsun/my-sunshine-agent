package com.sunshine.orchestrator.plan;

/** HITL / Recovery 阻塞态暂停时保留的待交互快照 */
public record PendingInteraction(
        String kind,
        String nodeId,
        String errorMessage,
        String hitlToolId,
        String hitlParamsSummary,
        String recoveryAttemptsJson) {
}
