package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.routing.ExecutionPlan;

/**
 * 执行层流式上下文 — 从 ChatController 传入 Dispatcher / Executor
 */
public record ExecutionStreamContext(
        String conversationId,
        String assistantMsgId,
        String userContent,
        MemoryContext memory,
        String existingContent,
        String existingReasoning,
        String legacyIntent,
        String userId,
        String tenantId,
        ExecutionPlan plan
) {
    public ExecutionStreamContext withPlan(ExecutionPlan newPlan) {
        return new ExecutionStreamContext(
                conversationId, assistantMsgId, userContent, memory,
                existingContent, existingReasoning, legacyIntent,
                userId, tenantId, newPlan);
    }
}
