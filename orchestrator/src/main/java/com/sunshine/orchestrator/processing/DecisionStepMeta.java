package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.DecisionOption;

import java.util.List;

/** ReAct request_decision — 挂在 decision 步骤 metadata.decision */
public record DecisionStepMeta(
        String token,
        String question,
        List<DecisionOption> options,
        boolean allowCustomInput,
        Long expiresAt,
        String choice,
        String customInput) {
}
