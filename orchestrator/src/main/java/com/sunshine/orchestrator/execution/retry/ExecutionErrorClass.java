package com.sunshine.orchestrator.execution.retry;

/** 执行错误分类 — 决定是否可重试 */
public enum ExecutionErrorClass {
    TIMEOUT,
    SERVICE_UNAVAILABLE,
    CIRCUIT_OPEN,
    VALIDATION,
    BUSINESS,
    UNKNOWN;

    public boolean retryableByDefault() {
        return this == TIMEOUT || this == SERVICE_UNAVAILABLE || this == CIRCUIT_OPEN;
    }
}
