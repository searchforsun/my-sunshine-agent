package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.hitl.WorkflowHitlScope;
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
        ExecutionPlan plan,
        String persistedPlanId,
        WorkflowHitlScope.Binding workflowHitl
) {
    public ExecutionStreamContext(
            String conversationId,
            String assistantMsgId,
            String userContent,
            MemoryContext memory,
            String existingContent,
            String existingReasoning,
            String legacyIntent,
            String userId,
            String tenantId,
            ExecutionPlan plan) {
        this(conversationId, assistantMsgId, userContent, memory,
                existingContent, existingReasoning, legacyIntent,
                userId, tenantId, plan, null, null);
    }

    public ExecutionStreamContext withPlan(ExecutionPlan newPlan) {
        return new ExecutionStreamContext(
                conversationId, assistantMsgId, userContent, memory,
                existingContent, existingReasoning, legacyIntent,
                userId, tenantId, newPlan, persistedPlanId, workflowHitl);
    }

    public ExecutionStreamContext withPersistedPlanId(String planId) {
        return new ExecutionStreamContext(
                conversationId, assistantMsgId, userContent, memory,
                existingContent, existingReasoning, legacyIntent,
                userId, tenantId, plan, planId, workflowHitl);
    }

    /** Workflow tool 节点 HITL — 跨线程随 streamCtx 传递，勿用 ThreadLocal */
    public ExecutionStreamContext withWorkflowHitl(WorkflowHitlScope.Binding binding) {
        return new ExecutionStreamContext(
                conversationId, assistantMsgId, userContent, memory,
                existingContent, existingReasoning, legacyIntent,
                userId, tenantId, plan, persistedPlanId, binding);
    }
}
