package com.sunshine.llm.router;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdapterCircuitBreakerTest {

    @Test
    void opensAfterRepeatedFailures() {
        AdapterCircuitBreaker breaker = new AdapterCircuitBreaker();
        String model = "deepseek-v4-pro";

        assertThat(breaker.allowRequest(model)).isTrue();
        breaker.recordFailure(model);
        breaker.recordFailure(model);
        assertThat(breaker.allowRequest(model)).isTrue();
        breaker.recordFailure(model);
        assertThat(breaker.allowRequest(model)).isFalse();
    }

    @Test
    void successResetsFailures() {
        AdapterCircuitBreaker breaker = new AdapterCircuitBreaker();
        String model = "deepseek-v4-pro";
        breaker.recordFailure(model);
        breaker.recordFailure(model);
        breaker.recordSuccess(model);
        assertThat(breaker.allowRequest(model)).isTrue();
    }
}
