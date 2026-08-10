package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.hitl.WorkflowHitlScope;
import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.plan.ResumeInteractionHint;
import com.sunshine.orchestrator.routing.ExecutionPlan;

/**
 * 执行层流式上下文 — 从 ChatController 传入 Dispatcher / Executor
 */
public record ExecutionStreamContext(
        String conversationId,
        String assistantMsgId,
        String userContent,
        AssembledContext memory,
        String existingContent,
        String existingReasoning,
        String userId,
        String tenantId,
        String kbId,
        ExecutionPlan plan,
        String persistedPlanId,
        WorkflowHitlScope.Binding workflowHitl,
        ResumeInteractionHint resumeInteraction,
        boolean workflowHitlPreApproved,
        boolean reactRestart,
        /** 续跑时已有的步骤 JSON；ReactExecutor 用于计算 checkpoint think 轮次 */
        String existingStepsJson,
        /** 用户个人规则（soul）；顶层执行路径注入提示词，子 Agent 不继承 */
        String personalRules,
        /** 会话类型：chat / task（task 会话使用更高的 ReAct 轮数上限） */
        String conversationKind,
        /** 会话绑定模型 override（MAIN chat） */
        String modelOverride) {
    public ExecutionStreamContext(
            String conversationId,
            String assistantMsgId,
            String userContent,
            AssembledContext memory,
            String existingContent,
            String existingReasoning,
            String userId,
            String tenantId,
            ExecutionPlan plan) {
        this(conversationId, assistantMsgId, userContent, memory,
                existingContent, existingReasoning,
                userId, tenantId, null, plan, null, null, null, false, false, null, null, null, null);
    }

    public ExecutionStreamContext(
            String conversationId,
            String assistantMsgId,
            String userContent,
            AssembledContext memory,
            String existingContent,
            String existingReasoning,
            String userId,
            String tenantId,
            ExecutionPlan plan,
            String persistedPlanId,
            WorkflowHitlScope.Binding workflowHitl,
            ResumeInteractionHint resumeInteraction,
            boolean workflowHitlPreApproved,
            boolean reactRestart,
            String existingStepsJson,
            String personalRules,
            String conversationKind) {
        this(conversationId, assistantMsgId, userContent, memory,
                existingContent, existingReasoning,
                userId, tenantId, null, plan, persistedPlanId, workflowHitl, resumeInteraction,
                workflowHitlPreApproved, reactRestart, existingStepsJson, personalRules, conversationKind, null);
    }

    /** 含 kbId、无 modelOverride（测试与旧调用兼容） */
    public ExecutionStreamContext(
            String conversationId,
            String assistantMsgId,
            String userContent,
            AssembledContext memory,
            String existingContent,
            String existingReasoning,
            String userId,
            String tenantId,
            String kbId,
            ExecutionPlan plan,
            String persistedPlanId,
            WorkflowHitlScope.Binding workflowHitl,
            ResumeInteractionHint resumeInteraction,
            boolean workflowHitlPreApproved,
            boolean reactRestart,
            String existingStepsJson,
            String personalRules,
            String conversationKind) {
        this(conversationId, assistantMsgId, userContent, memory,
                existingContent, existingReasoning,
                userId, tenantId, kbId, plan, persistedPlanId, workflowHitl, resumeInteraction,
                workflowHitlPreApproved, reactRestart, existingStepsJson, personalRules, conversationKind, null);
    }

    public ExecutionStreamContext(
            String conversationId,
            String assistantMsgId,
            String userContent,
            AssembledContext memory,
            String existingContent,
            String existingReasoning,
            String userId,
            String tenantId,
            ExecutionPlan plan,
            String persistedPlanId,
            WorkflowHitlScope.Binding workflowHitl,
            ResumeInteractionHint resumeInteraction,
            boolean workflowHitlPreApproved) {
        this(conversationId, assistantMsgId, userContent, memory,
                existingContent, existingReasoning,
                userId, tenantId, null, plan, persistedPlanId, workflowHitl, resumeInteraction,
                workflowHitlPreApproved, false, null, null, null, null);
    }

    public ExecutionStreamContext withPlan(ExecutionPlan newPlan) {
        return new ExecutionStreamContext(
                conversationId, assistantMsgId, userContent, memory,
                existingContent, existingReasoning,
                userId, tenantId, kbId, newPlan, persistedPlanId, workflowHitl, resumeInteraction,
                workflowHitlPreApproved, reactRestart, existingStepsJson, personalRules, conversationKind,
                modelOverride);
    }

    public ExecutionStreamContext withPersistedPlanId(String planId) {
        return new ExecutionStreamContext(
                conversationId, assistantMsgId, userContent, memory,
                existingContent, existingReasoning,
                userId, tenantId, kbId, plan, planId, workflowHitl, resumeInteraction,
                workflowHitlPreApproved, reactRestart, existingStepsJson, personalRules, conversationKind,
                modelOverride);
    }

    /** Workflow tool 节点 HITL — 跨线程随 streamCtx 传递，勿用 ThreadLocal */
    public ExecutionStreamContext withWorkflowHitl(WorkflowHitlScope.Binding binding) {
        return new ExecutionStreamContext(
                conversationId, assistantMsgId, userContent, memory,
                existingContent, existingReasoning,
                userId, tenantId, kbId, plan, persistedPlanId, binding, resumeInteraction,
                workflowHitlPreApproved, reactRestart, existingStepsJson, personalRules, conversationKind,
                modelOverride);
    }

    public ExecutionStreamContext withResumeInteraction(ResumeInteractionHint hint) {
        return new ExecutionStreamContext(
                conversationId, assistantMsgId, userContent, memory,
                existingContent, existingReasoning,
                userId, tenantId, kbId, plan, persistedPlanId, workflowHitl, hint,
                workflowHitlPreApproved, reactRestart, existingStepsJson, personalRules, conversationKind,
                modelOverride);
    }

    /** HITL 续跑 re-await 已确认，跳过 ToolNodeHandler 二次确认 */
    public ExecutionStreamContext withHitlPreApproved() {
        return new ExecutionStreamContext(
                conversationId, assistantMsgId, userContent, memory,
                existingContent, existingReasoning,
                userId, tenantId, kbId, plan, persistedPlanId, workflowHitl, null, true, reactRestart,
                existingStepsJson, personalRules, conversationKind, modelOverride);
    }
}
