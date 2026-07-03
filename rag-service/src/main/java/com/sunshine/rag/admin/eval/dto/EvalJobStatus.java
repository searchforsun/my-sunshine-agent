package com.sunshine.rag.admin.eval.dto;

import java.time.Instant;

public record EvalJobStatus(
        Long jobId,
        String tenantId,
        String kbId,
        String suite,
        String status,
        Long reportId,
        Long configVersionId,
        Integer totalItems,
        Integer processedItems,
        Double progressPct,
        Instant createdAt,
        Instant finishedAt) {
}
