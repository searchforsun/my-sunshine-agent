package com.sunshine.workflow.dto;

import java.util.Map;

public record WorkflowPublishedResponse(
        String workflowId,
        int version,
        Map<String, Object> plan,
        Map<String, Object> catalogMeta) {
}
