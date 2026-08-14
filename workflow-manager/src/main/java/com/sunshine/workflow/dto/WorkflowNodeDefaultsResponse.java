package com.sunshine.workflow.dto;

import java.util.List;
import java.util.Map;

public record WorkflowNodeDefaultsResponse(
        WorkflowNodeRetryDefaults defaults,
        Map<String, WorkflowNodeRetryDefaults> byType,
        double backoffMultiplier,
        List<String> retryOnErrorClass,
        WorkflowCatalogDefaults catalog,
        Map<String, WorkflowNodeParamDefaults> nodeParams) {
}
