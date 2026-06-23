package com.sunshine.orchestrator.routing.policy;

/** 路由链上下文 */
public record RoutingContext(String userMessage, String traceMessageId) {

    public static RoutingContext of(String userMessage) {
        return new RoutingContext(userMessage, null);
    }
}
