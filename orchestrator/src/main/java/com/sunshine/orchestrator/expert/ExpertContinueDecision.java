package com.sunshine.orchestrator.expert;

/** 每轮结束后的继续讨论判定 */
public record ExpertContinueDecision(boolean shouldContinue, String reason) {
    public static ExpertContinueDecision stop(String reason) {
        return new ExpertContinueDecision(false, reason);
    }

    public static ExpertContinueDecision proceed(String reason) {
        return new ExpertContinueDecision(true, reason);
    }
}
