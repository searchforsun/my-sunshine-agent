package com.sunshine.workflow.dto;

public record WorkflowCreateRequest(
        String id,
        String displayName,
        String description,
        String kind) {
}
