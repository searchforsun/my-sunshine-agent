package com.sunshine.rag.admin.config.dto;

import com.sunshine.rag.admin.eval.dto.SmokeEvalResult;

import java.time.Instant;

public record ConfigVersionSummary(
        Long id,
        int versionNo,
        String status,
        Instant createdAt,
        Instant publishedAt,
        boolean active,
        Double recallAt5,
        String changeNote,
        String createdBy) {
}
