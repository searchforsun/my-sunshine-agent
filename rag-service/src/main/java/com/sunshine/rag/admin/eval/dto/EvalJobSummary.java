package com.sunshine.rag.admin.eval.dto;

import java.time.Instant;

public record EvalJobSummary(
        long jobId,
        String kbId,
        String suite,
        String suiteKey,
        String status,
        Long configVersionId,
        Integer configVersionNo,
        Long reportId,
        Double recallAt5,
        Boolean passedGate,
        Instant createdAt,
        Instant finishedAt) {
}
