package com.sunshine.orchestrator.execution.agent;

/**
 * Agent 子节点入参
 */
public record AgentNodeInput(
        String query,
        String injectedContext,
        String maxIters
) {
}
