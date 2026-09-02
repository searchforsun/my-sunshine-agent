package com.sunshine.workflow.dto;

import java.time.Instant;

public record WorkflowListItem(
        String id,
        String displayName,
        String description,
        String kind,
        boolean enabled,
        int activeVersion,
        String source,
        Instant updatedAt,
        Instant activeVersionCreatedAt,
        boolean activeVersionPublished) {
}
