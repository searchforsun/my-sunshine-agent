package com.sunshine.workflow.dto;

import java.util.Map;

public record WorkflowDraftRequest(
        Map<String, Object> plan,
        Map<String, Object> catalog) {
}
