package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.routing.ExecutionPreference;

/** 路由链上下文 */
public record RoutingContext(
        String userMessage,
        String traceMessageId,
        ExecutionPreference preference,
        String forcedWorkflowId,
        String clientSkillId,
        MemoryContext memory) {

    public RoutingContext(String userMessage, String traceMessageId) {
        this(userMessage, traceMessageId, ExecutionPreference.AUTO, null, null, null);
    }

    public RoutingContext(
            String userMessage,
            String traceMessageId,
            ExecutionPreference preference,
            String forcedWorkflowId,
            String clientSkillId) {
        this(userMessage, traceMessageId, preference, forcedWorkflowId, clientSkillId, null);
    }

    public static RoutingContext of(String userMessage) {
        return new RoutingContext(userMessage, null, ExecutionPreference.AUTO, null, null, null);
    }

    public boolean allowsSkillBinding() {
        return preference == null || preference.allowsSkillBinding();
    }
}
