package com.sunshine.rag.admin.config.dto;

import java.time.Instant;
import java.util.Map;

public record ConfigDraftSummary(
        Long id,
        String scope,
        Map<String, Object> payload,
        String status,
        String createdBy,
        Instant createdAt,
        Instant publishedAt) {
}
