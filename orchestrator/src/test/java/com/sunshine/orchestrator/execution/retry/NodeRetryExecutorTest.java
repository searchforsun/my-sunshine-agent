package com.sunshine.orchestrator.execution.retry;

import com.sunshine.orchestrator.execution.NodeResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NodeRetryExecutorTest {

    private final NodeRetryExecutor executor = new NodeRetryExecutor(new ExecutionErrorClassifier());

    @Test
    void retriesOnTimeoutUntilSuccess() {
        NodeRetryPolicy policy = new NodeRetryPolicy(
                2, 0, 2.0, OnFailureAction.CONTINUE, Set.of("TIMEOUT"));
        int[] calls = {0};
        NodeRetryExecutor.AttemptOutcome outcome = executor.runWithRetry(policy, () -> {
            calls[0]++;
            if (calls[0] < 2) {
                return Mono.just(NodeResult.fail("调用超时"));
            }
            return Mono.just(NodeResult.ok(java.util.Map.of("output", "ok")));
        }).block();
        assertThat(outcome).isNotNull();
        assertThat(outcome.result().success()).isTrue();
        assertThat(outcome.attempts()).hasSize(2);
        assertThat(calls[0]).isEqualTo(2);
    }

    @Test
    void doesNotRetryValidationErrors() {
        NodeRetryPolicy policy = new NodeRetryPolicy(
                3, 0, 2.0, OnFailureAction.CONTINUE, Set.of("TIMEOUT"));
        int[] calls = {0};
        NodeRetryExecutor.AttemptOutcome outcome = executor.runWithRetry(policy, () -> {
            calls[0]++;
            return Mono.just(NodeResult.fail("缺少 status 参数"));
        }).block();
        assertThat(outcome).isNotNull();
        assertThat(outcome.result().success()).isFalse();
        assertThat(outcome.attempts()).hasSize(1);
        assertThat(calls[0]).isEqualTo(1);
    }
}
