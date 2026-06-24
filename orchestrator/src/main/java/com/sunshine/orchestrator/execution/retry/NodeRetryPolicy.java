package com.sunshine.orchestrator.execution.retry;

import java.util.Set;

/** 单节点重试与失败策略（解析后快照） */
public record NodeRetryPolicy(
        int maxAttempts,
        long backoffMs,
        double backoffMultiplier,
        OnFailureAction onFailure,
        Set<String> retryOnErrorClass
) {
    public static NodeRetryPolicy noRetry(OnFailureAction onFailure) {
        return new NodeRetryPolicy(1, 0, 1.0, onFailure, Set.of());
    }

    public long backoffForAttempt(int attemptNo) {
        if (attemptNo <= 1 || backoffMs <= 0) {
            return 0;
        }
        double factor = Math.pow(backoffMultiplier, attemptNo - 2);
        return (long) (backoffMs * factor);
    }
}
