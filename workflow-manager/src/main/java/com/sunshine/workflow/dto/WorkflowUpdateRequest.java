package com.sunshine.workflow.dto;

public record WorkflowUpdateRequest(
        String displayName,
        String description,
        String kind) {
}
