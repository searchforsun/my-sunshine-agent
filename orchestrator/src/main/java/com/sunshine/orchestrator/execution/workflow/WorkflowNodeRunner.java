package com.sunshine.orchestrator.execution.workflow;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeHandlerRegistry;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.StreamingNodeHandler;
import com.sunshine.orchestrator.execution.TemplateResolver;
import com.sunshine.orchestrator.execution.UpstreamOutputResolver;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.execution.WorkflowDefinition;
import com.sunshine.orchestrator.execution.WorkflowNodeLabels;
import com.sunshine.orchestrator.execution.WorkflowNodeTimeline;
import com.sunshine.orchestrator.execution.WorkflowStreamCollector;
import com.sunshine.orchestrator.execution.retry.NodeRetryExecutor;
import com.sunshine.orchestrator.execution.retry.NodeRetryPolicy;
import com.sunshine.orchestrator.execution.retry.NodeRetryPolicyResolver;
import com.sunshine.orchestrator.execution.retry.WorkflowRunSession;
import com.sunshine.orchestrator.hitl.HitlConfirmationService;
import com.sunshine.orchestrator.hitl.WorkflowHitlScope;
import com.sunshine.orchestrator.hitl.WorkflowNodeRecoveryService;
import com.sunshine.orchestrator.hitl.WorkflowRecoveryAction;
import com.sunshine.orchestrator.plan.PendingInteraction;
import com.sunshine.orchestrator.plan.ResumeInteractionHint;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Workflow 单节点执行：重试、HITL/Recovery 续跑、流式 handler */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowNodeRunner {

    private final NodeHandlerRegistry registry;
    private final NodeRetryPolicyResolver retryPolicyResolver;
    private final NodeRetryExecutor nodeRetryExecutor;
    private final UpstreamOutputResolver upstreamOutputResolver;
    private final HitlConfirmationService hitlConfirmationService;
    private final WorkflowNodeRecoveryService workflowNodeRecoveryService;
    private final WorkflowNodeFinalizer nodeFinalizer;

    public Flux<StreamToken> executeNode(
            ProcessingTimelineSession session,
            WorkflowDefinition def,
            String nodeId,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            WorkflowRunSession runSession,
            boolean planWorkflow) {
        if (runSession.isAborted()) {
            return Flux.empty();
        }
        NodeSpec rawSpec = def.node(nodeId);
        if (rawSpec == null) {
            log.warn("[WorkflowNodeRunner] 节点 {} 不存在", nodeId);
            return Flux.empty();
        }
        NodeSpec resolved = resolveParams(rawSpec, wfCtx, def, planWorkflow);
        NodeHandler handler = registry.require(rawSpec.type());
        ResumeInteractionHint hint = streamCtx.resumeInteraction();
        if (hint != null && nodeId.equals(hint.pending().nodeId())) {
            return resumeFromPendingInteraction(
                    session, def, nodeId, rawSpec, resolved, handler, wfCtx,
                    streamCtx.withResumeInteraction(null), runSession, planWorkflow, hint.pending());
        }
        boolean tracksNodeStep = WorkflowNodeLabels.tracksNodeStep(rawSpec.type());
        long startedAt = System.currentTimeMillis();
        NodeRetryPolicy retryPolicy = retryPolicyResolver.resolve(rawSpec, planWorkflow);
        List<StreamToken> startTokens = tracksNodeStep
                ? WorkflowNodeTimeline.start(session, nodeId, rawSpec.type(), rawSpec.displayName())
                : List.of();
        return Flux.concat(
                Flux.fromIterable(startTokens),
                runNode(session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                        tracksNodeStep, startedAt, retryPolicy, runSession, def)
        );
    }

    private Flux<StreamToken> resumeFromPendingInteraction(
            ProcessingTimelineSession session,
            WorkflowDefinition def,
            String nodeId,
            NodeSpec rawSpec,
            NodeSpec resolved,
            NodeHandler handler,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            WorkflowRunSession runSession,
            boolean planWorkflow,
            PendingInteraction pending) {
        boolean tracksNodeStep = WorkflowNodeLabels.tracksNodeStep(rawSpec.type());
        long startedAt = System.currentTimeMillis();
        NodeRetryPolicy retryPolicy = retryPolicyResolver.resolve(rawSpec, planWorkflow);
        if ("hitl".equals(pending.kind()) && "tool".equals(rawSpec.type())) {
            WorkflowHitlScope.Binding hitl = new WorkflowHitlScope.Binding(
                    session, WorkflowNodeTimeline.stepId(nodeId), streamCtx.assistantMsgId());
            return Mono.fromCallable(() -> hitlConfirmationService.resumeAwaitingFromCheckpoint(
                            hitl, streamCtx.assistantMsgId(), pending,
                            resolved.params().getOrDefault("tool", "")))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(approved -> {
                        if (!approved) {
                            Map<String, String> outputs = new LinkedHashMap<>();
                            String msg = hitlConfirmationService.rejectionMessage();
                            outputs.put("output", msg);
                            outputs.put("detail", msg);
                            return nodeFinalizer.finalizeNode(session, nodeId, rawSpec, NodeResult.ok(outputs), wfCtx, streamCtx,
                                    tracksNodeStep, startedAt, retryPolicy, List.of(), runSession);
                        }
                        ExecutionStreamContext approvedCtx = streamCtx.withHitlPreApproved().withWorkflowHitl(hitl);
                        return runNode(session, nodeId, rawSpec, resolved, handler, wfCtx, approvedCtx,
                                tracksNodeStep, startedAt, retryPolicy, runSession, def);
                    });
        }
        if ("recovery".equals(pending.kind())) {
            return Mono.fromCallable(() -> workflowNodeRecoveryService.resumeAwaiting(
                            session, nodeId, streamCtx.assistantMsgId(), pending, runSession))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMapMany(action -> applyRecoveryAction(
                            session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                            tracksNodeStep, startedAt, retryPolicy, runSession, def, action, pending.errorMessage()));
        }
        log.warn("[WorkflowNodeRunner] 未知 pendingInteraction kind={} node={}", pending.kind(), nodeId);
        return runNode(session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                tracksNodeStep, startedAt, retryPolicy, runSession, def);
    }

    private Flux<StreamToken> applyRecoveryAction(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            NodeSpec resolved,
            NodeHandler handler,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            boolean tracksNodeStep,
            long startedAt,
            NodeRetryPolicy retryPolicy,
            WorkflowRunSession runSession,
            WorkflowDefinition def,
            WorkflowRecoveryAction action,
            String errorMessage) {
        String err = StringUtils.hasText(errorMessage) ? errorMessage : "节点执行失败";
        NodeResult failed = NodeResult.fail(err);
        List<NodeRetryExecutor.PlanNodeAttemptRecord> attempts = List.of();
        if (action == WorkflowRecoveryAction.RETRY) {
            List<StreamToken> restart = tracksNodeStep
                    ? WorkflowNodeTimeline.restart(session, nodeId, rawSpec.type(), rawSpec.displayName())
                    : List.of();
            long retryStarted = System.currentTimeMillis();
            return Flux.concat(
                    Flux.fromIterable(restart),
                    runNode(session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                            tracksNodeStep, retryStarted, retryPolicy, runSession, def));
        }
        if (action == WorkflowRecoveryAction.SKIP) {
            NodeResult skipped = WorkflowNodeFinalizer.buildSkippedNodeResult(rawSpec, err);
            return nodeFinalizer.finalizeNode(session, nodeId, rawSpec, skipped, wfCtx, streamCtx,
                    tracksNodeStep, startedAt, retryPolicy, attempts, runSession);
        }
        return nodeFinalizer.finalizeNode(session, nodeId, rawSpec, failed, wfCtx, streamCtx,
                tracksNodeStep, startedAt, retryPolicy, attempts, runSession);
    }

    private Flux<StreamToken> runNode(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            NodeSpec resolved,
            NodeHandler handler,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            boolean tracksNodeStep,
            long startedAt,
            NodeRetryPolicy retryPolicy,
            WorkflowRunSession runSession,
            WorkflowDefinition def) {
        if (handler instanceof StreamingNodeHandler streaming) {
            return executeStreamingNode(
                    session, nodeId, rawSpec, resolved, streaming, wfCtx, streamCtx,
                    tracksNodeStep, startedAt, retryPolicy, runSession, def);
        }
        return nodeRetryExecutor.runWithRetry(
                        retryPolicy,
                        () -> wrapToolHitlScope(handler, resolved, wfCtx, streamCtx, session, nodeId),
                        nodeFinalizer.nodeAttemptsListener(session, nodeId, rawSpec, streamCtx, tracksNodeStep, startedAt, retryPolicy))
                .flatMapMany(outcome -> afterNodeOutcome(
                        session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                        tracksNodeStep, startedAt, retryPolicy, runSession, def, outcome));
    }

    private Flux<StreamToken> afterNodeOutcome(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            NodeSpec resolved,
            NodeHandler handler,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            boolean tracksNodeStep,
            long startedAt,
            NodeRetryPolicy retryPolicy,
            WorkflowRunSession runSession,
            WorkflowDefinition def,
            NodeRetryExecutor.AttemptOutcome outcome) {
        if (outcome.result().success()) {
            return nodeFinalizer.finalizeNode(session, nodeId, rawSpec, outcome.result(), wfCtx, streamCtx,
                    tracksNodeStep, startedAt, retryPolicy, outcome.attempts(), runSession);
        }
        return handleFailureWithRecovery(session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                tracksNodeStep, startedAt, retryPolicy, runSession, def, outcome);
    }

    private Flux<StreamToken> handleFailureWithRecovery(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            NodeSpec resolved,
            NodeHandler handler,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            boolean tracksNodeStep,
            long startedAt,
            NodeRetryPolicy retryPolicy,
            WorkflowRunSession runSession,
            WorkflowDefinition def,
            NodeRetryExecutor.AttemptOutcome outcome) {
        if (!workflowNodeRecoveryService.isEnabled() || !StringUtils.hasText(streamCtx.persistedPlanId())) {
            return nodeFinalizer.finalizeNode(session, nodeId, rawSpec, outcome.result(), wfCtx, streamCtx,
                    tracksNodeStep, startedAt, retryPolicy, outcome.attempts(), runSession);
        }
        String err = outcome.result().safeOutputs().getOrDefault("error", "节点执行失败");
        return Mono.fromCallable(() -> workflowNodeRecoveryService.awaitRecovery(
                        session, nodeId, streamCtx.assistantMsgId(), err, runSession))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(action -> {
                    if (action == WorkflowRecoveryAction.RETRY) {
                        List<StreamToken> restart = tracksNodeStep
                                ? WorkflowNodeTimeline.restart(session, nodeId, rawSpec.type(), rawSpec.displayName())
                                : List.of();
                        long retryStarted = System.currentTimeMillis();
                        return Flux.concat(
                                Flux.fromIterable(restart),
                                runNode(session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                                        tracksNodeStep, retryStarted, retryPolicy, runSession, def));
                    }
                    if (action == WorkflowRecoveryAction.SKIP) {
                        NodeResult skipped = WorkflowNodeFinalizer.buildSkippedNodeResult(rawSpec, err);
                        return nodeFinalizer.finalizeNode(session, nodeId, rawSpec, skipped, wfCtx, streamCtx,
                                tracksNodeStep, startedAt, retryPolicy, outcome.attempts(), runSession);
                    }
                    return nodeFinalizer.finalizeNode(session, nodeId, rawSpec, outcome.result(), wfCtx, streamCtx,
                            tracksNodeStep, startedAt, retryPolicy, outcome.attempts(), runSession);
                });
    }

    private Mono<NodeResult> wrapToolHitlScope(
            NodeHandler handler,
            NodeSpec resolved,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            ProcessingTimelineSession session,
            String nodeId) {
        boolean toolNode = "tool".equals(resolved.type());
        if (!toolNode) {
            return handler.run(resolved, wfCtx, streamCtx);
        }
        ExecutionStreamContext hitlCtx = streamCtx.withWorkflowHitl(new WorkflowHitlScope.Binding(
                session,
                WorkflowNodeTimeline.stepId(nodeId),
                streamCtx.assistantMsgId()));
        return handler.run(resolved, wfCtx, hitlCtx);
    }

    private Flux<StreamToken> executeStreamingNode(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            NodeSpec resolved,
            StreamingNodeHandler handler,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            boolean tracksNodeStep,
            long startedAt,
            NodeRetryPolicy retryPolicy,
            WorkflowRunSession runSession,
            WorkflowDefinition def) {
        return streamingAttempt(
                session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                tracksNodeStep, startedAt, retryPolicy, runSession, def, 1, new ArrayList<>());
    }

    private Flux<StreamToken> streamingAttempt(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            NodeSpec resolved,
            StreamingNodeHandler handler,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            boolean tracksNodeStep,
            long startedAt,
            NodeRetryPolicy retryPolicy,
            WorkflowRunSession runSession,
            WorkflowDefinition def,
            int attemptNo,
            List<NodeRetryExecutor.PlanNodeAttemptRecord> attempts) {
        long attemptStarted = System.currentTimeMillis();
        WorkflowStreamCollector collector = handler.createStreamCollector(resolved, nodeId);
        return handler.streamTokens(resolved, wfCtx, streamCtx, nodeId, collector)
                .doOnNext(collector::accept)
                .concatWith(Flux.defer(() -> {
                    NodeResult result = handler.buildResult(collector);
                    long attemptEnded = System.currentTimeMillis();
                    if (result.success()) {
                        attempts.add(new NodeRetryExecutor.PlanNodeAttemptRecord(
                                attemptNo, "completed", null, "完成", attemptStarted, attemptEnded));
                        return nodeFinalizer.finalizeNode(session, nodeId, rawSpec, result, wfCtx, streamCtx,
                                tracksNodeStep, startedAt, retryPolicy, attempts, runSession);
                    }
                    String err = result.safeOutputs().getOrDefault("error", "节点执行失败");
                    attempts.add(new NodeRetryExecutor.PlanNodeAttemptRecord(
                            attemptNo, "failed", null, "失败: " + err, attemptStarted, attemptEnded));
                    nodeFinalizer.publishNodeAttemptsProgress(session, nodeId, rawSpec, streamCtx, tracksNodeStep,
                            startedAt, retryPolicy, attempts);
                    if (attemptNo < retryPolicy.maxAttempts() && isStreamRetryable(err, retryPolicy)) {
                        long delay = retryPolicy.backoffForAttempt(attemptNo + 1);
                        Flux<StreamToken> next = streamingAttempt(
                                session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                                tracksNodeStep, startedAt, retryPolicy, runSession, def, attemptNo + 1, attempts);
                        return delay > 0
                                ? Mono.delay(java.time.Duration.ofMillis(delay)).thenMany(next)
                                : next;
                    }
                    return handleFailureWithRecovery(session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                            tracksNodeStep, startedAt, retryPolicy, runSession, def,
                            new NodeRetryExecutor.AttemptOutcome(result, attempts));
                }))
                .onErrorResume(Throwable.class, e -> {
                    long attemptEnded = System.currentTimeMillis();
                    String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    attempts.add(new NodeRetryExecutor.PlanNodeAttemptRecord(
                            attemptNo, "failed", null, "失败: " + err, attemptStarted, attemptEnded));
                    nodeFinalizer.publishNodeAttemptsProgress(session, nodeId, rawSpec, streamCtx, tracksNodeStep,
                            startedAt, retryPolicy, attempts);
                    if (attemptNo < retryPolicy.maxAttempts() && isStreamRetryable(err, retryPolicy)) {
                        long delay = retryPolicy.backoffForAttempt(attemptNo + 1);
                        Flux<StreamToken> next = streamingAttempt(
                                session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                                tracksNodeStep, startedAt, retryPolicy, runSession, def, attemptNo + 1, attempts);
                        return delay > 0
                                ? Mono.delay(java.time.Duration.ofMillis(delay)).thenMany(next)
                                : next;
                    }
                    return handleFailureWithRecovery(session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                            tracksNodeStep, startedAt, retryPolicy, runSession, def,
                            new NodeRetryExecutor.AttemptOutcome(NodeResult.fail(err), attempts));
                });
    }

    private static boolean isStreamRetryable(String err, NodeRetryPolicy policy) {
        if (policy.maxAttempts() <= 1) {
            return false;
        }
        String lower = err != null ? err.toLowerCase() : "";
        return lower.contains("timeout") || lower.contains("超时")
                || lower.contains("503") || lower.contains("502") || lower.contains("不可用");
    }

    private NodeSpec resolveParams(NodeSpec spec, WorkflowContext ctx, WorkflowDefinition def, boolean planWorkflow) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (spec.params() != null) {
            spec.params().forEach((k, v) -> {
                if (planWorkflow && "prompt".equals(k)) {
                    resolved.put(k, upstreamOutputResolver.resolvePrompt(v, ctx, def));
                } else {
                    resolved.put(k, TemplateResolver.resolve(v, ctx));
                }
            });
        }
        return new NodeSpec(spec.id(), spec.type(), resolved, spec.displayName());
    }
}
