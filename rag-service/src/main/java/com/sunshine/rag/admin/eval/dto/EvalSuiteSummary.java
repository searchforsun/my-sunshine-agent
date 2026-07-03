package com.sunshine.rag.admin.eval.dto;

import java.time.Instant;

public record EvalSuiteSummary(
        long id,
        String suiteKey,
        String displayName,
        String kind,
        String format,
        int itemCount,
        String status,
        boolean builtin,
        Instant createdAt) {
}
