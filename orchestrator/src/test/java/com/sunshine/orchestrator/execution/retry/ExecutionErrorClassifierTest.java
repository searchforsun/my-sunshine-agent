package com.sunshine.orchestrator.execution.retry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionErrorClassifierTest {

    private final ExecutionErrorClassifier classifier = new ExecutionErrorClassifier();

    @Test
    void classifiesTimeoutAsRetryable() {
        assertThat(classifier.classifyMessage("调用超时")).isEqualTo(ExecutionErrorClass.TIMEOUT);
        assertThat(classifier.isRetryable(ExecutionErrorClass.TIMEOUT, null)).isTrue();
    }

    @Test
    void classifiesValidationAsNonRetryableByDefault() {
        assertThat(classifier.classifyMessage("缺少 companyCode 参数")).isEqualTo(ExecutionErrorClass.VALIDATION);
        assertThat(classifier.isRetryable(ExecutionErrorClass.VALIDATION, null)).isFalse();
    }
}
