package com.sunshine.orchestrator.agent;

import java.util.List;

/** request_decision 单题（含选项与多选开关） */
public record DecisionQuestion(
        String id,
        String prompt,
        List<DecisionOption> options,
        boolean allowMultiple) {
}
