package com.sunshine.common.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** Plan / Workflow 节点默认重试策略 SSOT；节点级覆盖见 Studio params retry.* */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanWorkflowExecutionPolicy(
        NodeDefaults defaults,
        Map<String, NodeTypeOverride> byType,
        String criticalOnFailure) {

    public static PlanWorkflowExecutionPolicy platformDefault() {
        return new PlanWorkflowExecutionPolicy(
                new NodeDefaults(2, 500L, 2.0, "continue",
                        List.of("TIMEOUT", "SERVICE_UNAVAILABLE", "CIRCUIT_OPEN")),
                Map.of(
                        "rag", new NodeTypeOverride(1, null),
                        "tool", new NodeTypeOverride(2, null),
                        "agent", new NodeTypeOverride(1, null),
                        "answer", new NodeTypeOverride(2, "fail_fast")),
                "fail_fast");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NodeDefaults(
            int maxAttempts,
            long backoffMs,
            double backoffMultiplier,
            String onFailure,
            List<String> retryOnErrorClass) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NodeTypeOverride(
            Integer maxAttempts,
            String onFailure) {
    }
}
