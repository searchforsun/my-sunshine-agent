package com.sunshine.orchestrator.agent;

/** request_decision 选项（SSE metadata.decision.options） */
public record DecisionOption(String id, String label) {
    public static final String CUSTOM_ID = "__custom__";
}
