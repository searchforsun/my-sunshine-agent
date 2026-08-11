package com.sunshine.orchestrator.agent;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 内存侧等待用户决策的 Future（同实例内唤醒阻塞的 request_decision） */
record DecisionPendingWaiter(
        String messageId,
        String userId,
        String question,
        List<DecisionOption> options,
        boolean allowCustomInput,
        long expiresAt,
        CompletableFuture<DecisionResult> future) {
}
