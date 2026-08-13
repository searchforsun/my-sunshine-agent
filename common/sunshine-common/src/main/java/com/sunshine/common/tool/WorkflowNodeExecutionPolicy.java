package com.sunshine.common.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** Workflow 节点默认重试策略 SSOT；节点级覆盖见 Studio params retry.* */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowNodeExecutionPolicy(
        NodeDefaults defaults,
        Map<String, NodeTypeOverride> byType,
        String criticalOnFailure) {

    /**
     * 冷启动 / 测试占位。运行时以 workflow-manager Nacos → HTTP `/node-defaults` 为准；
     * 禁止在 fetch 失败时反复回退到本方法覆盖已成功加载的策略。
     */
    public static WorkflowNodeExecutionPolicy platformDefault() {
        return new WorkflowNodeExecutionPolicy(
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
