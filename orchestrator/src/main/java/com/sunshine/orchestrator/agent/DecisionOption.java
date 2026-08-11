package com.sunshine.orchestrator.agent;

/** request_decision 选项（SSE metadata.decision.options） */
public record DecisionOption(
        String value,
        String label,
        String description,
        boolean requireInput) {
}
