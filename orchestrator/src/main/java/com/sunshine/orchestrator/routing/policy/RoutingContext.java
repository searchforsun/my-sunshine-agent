package com.sunshine.orchestrator.routing.policy;

import com.sunshine.orchestrator.routing.ExecutionPreference;

/** 路由链上下文 */
public record RoutingContext(
        String userMessage,
        String traceMessageId,
        ExecutionPreference preference,
        String forcedWorkflowId,
        String clientSkillId) {

    public RoutingContext(String userMessage, String traceMessageId) {
        this(userMessage, traceMessageId, ExecutionPreference.AUTO, null, null);
    }

    public static RoutingContext of(String userMessage) {
        return new RoutingContext(userMessage, null, ExecutionPreference.AUTO, null, null);
    }

    public boolean allowsSkillBinding() {
        return preference == null || preference.allowsSkillBinding();
    }
}
