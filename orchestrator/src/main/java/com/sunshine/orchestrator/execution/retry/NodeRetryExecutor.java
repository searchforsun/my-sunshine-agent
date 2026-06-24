package com.sunshine.orchestrator.execution.retry;

import com.sunshine.orchestrator.execution.NodeResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 节点级同参重试执行器 */
@Component
@RequiredArgsConstructor
public class NodeRetryExecutor {

    private final ExecutionErrorClassifier errorClassifier;

    public record AttemptOutcome(
            NodeResult result,
            List<PlanNodeAttemptRecord> attempts
    ) {
    }

    /** 单次 attempt 记录（写入 PlanNodeTrace） */
    public record PlanNodeAttemptRecord(
            int attemptNo,
            String status,
            String errorClass,
            String summary,
            long startedAt,
            long endedAt
    ) {
    }

    public Mono<AttemptOutcome> runWithRetry(
            NodeRetryPolicy policy,
            Supplier<Mono<NodeResult>> runOnce) {
        return Mono.defer(() -> executeLoop(policy, runOnce, 1, new ArrayList<>()));
    }

    private Mono<AttemptOutcome> executeLoop(
            NodeRetryPolicy policy,
            Supplier<Mono<NodeResult>> runOnce,
            int attemptNo,
            List<PlanNodeAttemptRecord> attempts) {
        long startedAt = System.currentTimeMillis();
        return runOnce.get()
                .flatMap(result -> {
                    long endedAt = System.currentTimeMillis();
                    if (result.success()) {
                        attempts.add(new PlanNodeAttemptRecord(
                                attemptNo, "completed", null, "完成", startedAt, endedAt));
                        return Mono.just(new AttemptOutcome(result, List.copyOf(attempts)));
                    }
                    String err = result.safeOutputs().getOrDefault("error", "节点执行失败");
                    ExecutionErrorClass errorClass = errorClassifier.classifyMessage(err);
                    boolean retryable = errorClassifier.isRetryable(errorClass, policy.retryOnErrorClass());
                    attempts.add(new PlanNodeAttemptRecord(
                            attemptNo, "failed", errorClass.name(), "失败: " + err, startedAt, endedAt));
                    if (retryable && attemptNo < policy.maxAttempts()) {
                        long delay = policy.backoffForAttempt(attemptNo + 1);
                        Mono<AttemptOutcome> next = executeLoop(policy, runOnce, attemptNo + 1, attempts);
                        if (delay > 0) {
                            return Mono.delay(Duration.ofMillis(delay))
                                    .then(next);
                        }
                        return next;
                    }
                    return Mono.just(new AttemptOutcome(result, List.copyOf(attempts)));
                })
                .onErrorResume(e -> {
                    long endedAt = System.currentTimeMillis();
                    ExecutionErrorClass errorClass = errorClassifier.classify(e);
                    boolean retryable = errorClassifier.isRetryable(errorClass, policy.retryOnErrorClass());
                    String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    attempts.add(new PlanNodeAttemptRecord(
                            attemptNo, "failed", errorClass.name(), "失败: " + err, startedAt, endedAt));
                    if (retryable && attemptNo < policy.maxAttempts()) {
                        long delay = policy.backoffForAttempt(attemptNo + 1);
                        Mono<AttemptOutcome> next = executeLoop(policy, runOnce, attemptNo + 1, attempts);
                        if (delay > 0) {
                            return Mono.delay(Duration.ofMillis(delay))
                                    .subscribeOn(Schedulers.parallel())
                                    .then(next);
                        }
                        return next;
                    }
                    return Mono.just(new AttemptOutcome(NodeResult.fail(err), List.copyOf(attempts)));
                });
    }
}
