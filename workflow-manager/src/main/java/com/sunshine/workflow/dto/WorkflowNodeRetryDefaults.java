package com.sunshine.workflow.dto;

/** 节点执行策略（重试）默认值 — 已按类型合并全局 default */
public record WorkflowNodeRetryDefaults(
        int maxAttempts,
        long backoffMs,
        String onFailure) {
}
