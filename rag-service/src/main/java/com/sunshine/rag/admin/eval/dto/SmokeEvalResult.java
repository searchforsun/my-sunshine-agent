package com.sunshine.rag.admin.eval.dto;

import java.util.List;

public record SmokeEvalResult(
        double recallAt5,
        double baselineRecallAt5,
        boolean passedGate,
        List<FailedEvalSample> failedSamples) {
}
