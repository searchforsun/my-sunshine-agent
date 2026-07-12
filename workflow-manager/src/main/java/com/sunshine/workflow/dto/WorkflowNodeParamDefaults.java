package com.sunshine.workflow.dto;

public record WorkflowNodeParamDefaults(
        Integer topK,
        String kbIdEmptyLabel,
        Integer maxIters) {
}
