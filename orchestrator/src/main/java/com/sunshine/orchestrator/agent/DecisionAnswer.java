package com.sunshine.orchestrator.agent;

import java.util.List;

/** 用户对单题的作答 */
public record DecisionAnswer(
        String questionId,
        List<String> selectedOptionIds,
        String customInput) {
}
