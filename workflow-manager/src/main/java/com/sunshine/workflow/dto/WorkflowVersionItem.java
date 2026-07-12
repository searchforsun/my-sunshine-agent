package com.sunshine.workflow.dto;

import java.time.Instant;

public record WorkflowVersionItem(
        long id,
        String workflowId,
        int version,
        String status,
        Instant createdAt,
        Instant publishedAt) {
}
