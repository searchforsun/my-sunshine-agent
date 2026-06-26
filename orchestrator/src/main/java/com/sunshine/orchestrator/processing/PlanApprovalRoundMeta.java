package com.sunshine.orchestrator.processing;

/** Plan 确认轮次 — SSE metadata 与持久化对齐 */
public record PlanApprovalRoundMeta(
        int roundNo,
        String status,
        String userHint,
        String chainSummary,
        Long createdAt,
        Long resolvedAt) {

    public static PlanApprovalRoundMeta from(com.sunshine.orchestrator.plan.PlanApprovalRound round) {
        if (round == null) {
            return null;
        }
        return new PlanApprovalRoundMeta(
                round.roundNo(),
                round.status(),
                round.userHint(),
                round.chainSummary(),
                round.createdAt(),
                round.resolvedAt());
    }
}
