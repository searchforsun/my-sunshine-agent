package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.context.AssembledContext;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPreference;

/** 路由链上下文 */
public record RoutingContext(
        String userMessage,
        String traceMessageId,
        ExecutionPreference preference,
        String forcedWorkflowId,
        String clientSkillId,
        AssembledContext memory,
        /** 强制模式：L3 锁死 mode，仅解析绑定 */
        ExecutionMode lockedMode) {

    public RoutingContext(String userMessage, String traceMessageId) {
        this(userMessage, traceMessageId, ExecutionPreference.FAST, null, null, null, null);
    }

    public RoutingContext(
            String userMessage,
            String traceMessageId,
            ExecutionPreference preference,
            String forcedWorkflowId,
            String clientSkillId) {
        this(userMessage, traceMessageId, preference, forcedWorkflowId, clientSkillId, null, null);
    }

    public RoutingContext(
            String userMessage,
            String traceMessageId,
            ExecutionPreference preference,
            String forcedWorkflowId,
            String clientSkillId,
            AssembledContext memory) {
        this(userMessage, traceMessageId, preference, forcedWorkflowId, clientSkillId, memory, null);
    }

    public static RoutingContext of(String userMessage) {
        return new RoutingContext(userMessage, null, ExecutionPreference.FAST, null, null, null, null);
    }

    public RoutingContext withLockedMode(ExecutionMode mode) {
        return new RoutingContext(
                userMessage, traceMessageId, preference, forcedWorkflowId, clientSkillId, memory, mode);
    }

    public boolean allowsSkillBinding() {
        return preference == null || preference.allowsSkillBinding();
    }
}
