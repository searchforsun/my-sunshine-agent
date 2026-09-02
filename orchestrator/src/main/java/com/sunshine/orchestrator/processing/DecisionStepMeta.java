package com.sunshine.orchestrator.processing;

import com.sunshine.orchestrator.agent.DecisionAnswer;
import com.sunshine.orchestrator.agent.DecisionQuestion;

import java.util.List;

/** ReAct request_decision — 挂在 decision 步骤 metadata.decision */
public record DecisionStepMeta(
        String token,
        String title,
        List<DecisionQuestion> questions,
        Long expiresAt,
        String outcome,
        List<DecisionAnswer> answers) {
}
