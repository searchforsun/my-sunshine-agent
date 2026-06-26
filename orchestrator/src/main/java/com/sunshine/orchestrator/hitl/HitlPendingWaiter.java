package com.sunshine.orchestrator.hitl;

import java.util.concurrent.CompletableFuture;

/** 内存侧等待用户确认的 Future（同实例内唤醒阻塞的工具调用） */
record HitlPendingWaiter(
        String messageId,
        String toolId,
        CompletableFuture<Boolean> future) {
}
