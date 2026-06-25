package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentGroundingProperties;
import com.sunshine.orchestrator.grounding.AnswerGroundingChecker;
import com.sunshine.orchestrator.grounding.GroundingEvidenceSupport;
import com.sunshine.orchestrator.grounding.GroundingVerdict;
import com.sunshine.orchestrator.execution.retry.NodeRetryExecutor;
import com.sunshine.orchestrator.execution.retry.NodeRetryPolicy;
import com.sunshine.orchestrator.execution.retry.NodeRetryPolicyResolver;
import com.sunshine.orchestrator.execution.retry.OnFailureAction;
import com.sunshine.orchestrator.execution.retry.WorkflowRunSession;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PlanDisplayNameEnricher;
import com.sunshine.orchestrator.plan.PlanExecutionAuditService;
import com.sunshine.orchestrator.plan.PlanJson;
import com.sunshine.orchestrator.plan.PlanNodeAttempt;
import com.sunshine.orchestrator.plan.PlanNodeTrace;
import com.sunshine.orchestrator.plan.PlanRunFinalizer;
import com.sunshine.orchestrator.plan.PlanTimeline;
import com.sunshine.orchestrator.plan.StaticPlanAdapter;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
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
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Workflow DAG 线性执行引擎
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowExecutor {

    private final WorkflowDefinitionLoader loader;
    private final NodeHandlerRegistry registry;
    private final ExecutionPlanStore executionPlanStore;
    private final WorkflowNodeLabelService labelService;
    private final NodeRetryPolicyResolver retryPolicyResolver;
    private final NodeRetryExecutor nodeRetryExecutor;
    private final UpstreamOutputResolver upstreamOutputResolver;
    private final PlanExecutionAuditService planExecutionAuditService;
    private final PlanDisplayNameEnricher displayNameEnricher;
    private final PlanRunFinalizer planRunFinalizer;
    private final AnswerGroundingChecker groundingChecker;
    private final AgentGroundingProperties groundingProperties;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        ExecutionPlan plan = ctx.plan();
        if (plan == null || plan.mode() != ExecutionMode.WORKFLOW) {
            return Flux.just(StreamToken.content("内部错误：WorkflowExecutor 收到非 workflow 计划"));
        }
        String workflowId = plan.workflowId();
        Optional<WorkflowDefinition> defOpt = loader.load(workflowId);
        if (defOpt.isEmpty()) {
            log.error("[WorkflowExecutor] 未找到 workflow 定义: {}", workflowId);
            return Flux.just(StreamToken.content(
                    "工作流「" + workflowId + "」未定义，请联系管理员。"));
        }
        WorkflowDefinition def = defOpt.get();
        PlanJson rawPlan = StaticPlanAdapter.from(def, plan.reason());
        return Mono.fromCallable(() -> executionPlanStore.createDraft(ctx, rawPlan))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(planId -> planExecutionAuditService.created(
                        ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(), planId))
                .flatMapMany(planId -> runStaticAsPlan(ctx, planId, def, rawPlan));
    }

    /** 静态 workflow 物化为 Plan 并走与 plan-workflow 相同的 DAG 执行与终态路径 */
    private Flux<StreamToken> runStaticAsPlan(
            ExecutionStreamContext ctx,
            String planId,
            WorkflowDefinition def,
            PlanJson rawPlan) {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(ctx.userContent());
        session.bindTraceMessageId(ctx.assistantMsgId());
        PlanJson enriched = displayNameEnricher.enrich(rawPlan);
        List<StreamToken> planTokens = PlanTimeline.planStep(session, enriched, planId);
        ExecutionStreamContext execCtx = ctx.withPersistedPlanId(planId);
        WorkflowRunSession runSession = new WorkflowRunSession();
        int nodeCount = enriched.nodes().size();
        log.info("[WorkflowExecutor] 静态工作流 {} 物化为 Plan id={} 链={}",
                def.id(), planId, PlanTimeline.planChainSummary(enriched));
        return Mono.fromRunnable(() -> {
                    executionPlanStore.markValidated(planId, enriched);
                    executionPlanStore.markRunning(planId);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(v -> planExecutionAuditService.validated(
                        ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(),
                        planId, nodeCount))
                .thenMany(Flux.concat(
                        Flux.fromIterable(planTokens),
                        executeDynamicDefinition(def, execCtx, runSession)
                                .concatWith(Flux.defer(() -> planRunFinalizer.postWorkflow(ctx, planId, runSession)))
                                .doOnError(err -> {
                                    executionPlanStore.markFailed(planId, err.getMessage());
                                    planExecutionAuditService.failed(
                                            ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(),
                                            ctx.tenantId(), planId, err.getMessage());
                                })
                ));
    }

    /** 动态 Plan 物化后的 DAG 执行（plan 步由 PlanWorkflowExecutor 前置下发） */
    public Flux<StreamToken> executeDynamicDefinition(WorkflowDefinition def, ExecutionStreamContext streamCtx) {
        return executeDynamicDefinition(def, streamCtx, new WorkflowRunSession());
    }

    public Flux<StreamToken> executeDynamicDefinition(
            WorkflowDefinition def,
            ExecutionStreamContext streamCtx,
            WorkflowRunSession runSession) {
        labelService.bindRuntimeNodeLabels(def);
        WorkflowContext wfCtx = initContext(streamCtx);
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(streamCtx.userContent());
        session.bindTraceMessageId(streamCtx.assistantMsgId());
        boolean planWorkflow = StringUtils.hasText(streamCtx.persistedPlanId());
        return Flux.fromIterable(def.linearOrder())
                .concatMap(nodeId -> executeNode(
                        session, def, nodeId, wfCtx, streamCtx, runSession, planWorkflow))
                .doFinally(signal -> labelService.clearRuntimeNodeLabels());
    }

    private static WorkflowContext initContext(ExecutionStreamContext streamCtx) {
        WorkflowContext wfCtx = new WorkflowContext();
        Map<String, String> start = new LinkedHashMap<>();
        if (StringUtils.hasText(streamCtx.userContent())) {
            start.put("userQuery", streamCtx.userContent());
        }
        wfCtx.putNode("start", start);
        ExecutionPlan plan = streamCtx.plan();
        if (plan != null && plan.params() != null) {
            wfCtx.putNode("plan", new LinkedHashMap<>(plan.params()));
        }
        return wfCtx;
    }

    private Flux<StreamToken> executeNode(
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
            log.warn("[WorkflowExecutor] 节点 {} 不存在", nodeId);
            return Flux.empty();
        }
        NodeSpec resolved = resolveParams(rawSpec, wfCtx, def, planWorkflow);
        NodeHandler handler = registry.require(rawSpec.type());
        boolean showTimeline = WorkflowNodeLabels.isVisibleNode(rawSpec.type());
        long startedAt = System.currentTimeMillis();
        NodeRetryPolicy retryPolicy = retryPolicyResolver.resolve(rawSpec, planWorkflow);
        List<StreamToken> startTokens = showTimeline
                ? WorkflowNodeTimeline.start(session, nodeId, rawSpec.type(), rawSpec.displayName())
                : List.of();
        return Flux.concat(
                Flux.fromIterable(startTokens),
                runNode(session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                        showTimeline, startedAt, retryPolicy, runSession, def)
        );
    }

    private Flux<StreamToken> runNode(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            NodeSpec resolved,
            NodeHandler handler,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            boolean showTimeline,
            long startedAt,
            NodeRetryPolicy retryPolicy,
            WorkflowRunSession runSession,
            WorkflowDefinition def) {
        if (handler instanceof StreamingNodeHandler streaming) {
            return executeStreamingNode(
                    session, nodeId, rawSpec, resolved, streaming, wfCtx, streamCtx,
                    showTimeline, startedAt, retryPolicy, runSession, def);
        }
        return nodeRetryExecutor.runWithRetry(retryPolicy, () -> handler.run(resolved, wfCtx, streamCtx))
                .flatMapMany(outcome -> finalizeNode(
                        session, nodeId, rawSpec, outcome.result(), wfCtx, streamCtx,
                        showTimeline, startedAt, retryPolicy, outcome.attempts(), runSession));
    }

    private Flux<StreamToken> executeStreamingNode(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            NodeSpec resolved,
            StreamingNodeHandler handler,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            boolean showTimeline,
            long startedAt,
            NodeRetryPolicy retryPolicy,
            WorkflowRunSession runSession,
            WorkflowDefinition def) {
        return streamingAttempt(
                session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                showTimeline, startedAt, retryPolicy, runSession, def, 1, new ArrayList<>());
    }

    private Flux<StreamToken> streamingAttempt(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            NodeSpec resolved,
            StreamingNodeHandler handler,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            boolean showTimeline,
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
                        return finalizeNode(session, nodeId, rawSpec, result, wfCtx, streamCtx,
                                showTimeline, startedAt, retryPolicy, attempts, runSession);
                    }
                    String err = result.safeOutputs().getOrDefault("error", "节点执行失败");
                    attempts.add(new NodeRetryExecutor.PlanNodeAttemptRecord(
                            attemptNo, "failed", null, "失败: " + err, attemptStarted, attemptEnded));
                    if (attemptNo < retryPolicy.maxAttempts() && isStreamRetryable(err, retryPolicy)) {
                        long delay = retryPolicy.backoffForAttempt(attemptNo + 1);
                        Flux<StreamToken> next = streamingAttempt(
                                session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                                showTimeline, startedAt, retryPolicy, runSession, def, attemptNo + 1, attempts);
                        return delay > 0
                                ? Mono.delay(java.time.Duration.ofMillis(delay)).thenMany(next)
                                : next;
                    }
                    return finalizeNode(session, nodeId, rawSpec, result, wfCtx, streamCtx,
                            showTimeline, startedAt, retryPolicy, attempts, runSession);
                }))
                .onErrorResume(Throwable.class, e -> {
                    long attemptEnded = System.currentTimeMillis();
                    String err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    attempts.add(new NodeRetryExecutor.PlanNodeAttemptRecord(
                            attemptNo, "failed", null, "失败: " + err, attemptStarted, attemptEnded));
                    if (attemptNo < retryPolicy.maxAttempts() && isStreamRetryable(err, retryPolicy)) {
                        long delay = retryPolicy.backoffForAttempt(attemptNo + 1);
                        Flux<StreamToken> next = streamingAttempt(
                                session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx,
                                showTimeline, startedAt, retryPolicy, runSession, def, attemptNo + 1, attempts);
                        return delay > 0
                                ? Mono.delay(java.time.Duration.ofMillis(delay)).thenMany(next)
                                : next;
                    }
                    return finalizeNode(session, nodeId, rawSpec, NodeResult.fail(err), wfCtx, streamCtx,
                            showTimeline, startedAt, retryPolicy, attempts, runSession);
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

    private Flux<StreamToken> finalizeNode(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            NodeResult result,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            boolean showTimeline,
            long startedAt,
            NodeRetryPolicy retryPolicy,
            List<NodeRetryExecutor.PlanNodeAttemptRecord> attemptRecords,
            WorkflowRunSession runSession) {
        long endedAt = System.currentTimeMillis();
        int attemptCount = attemptRecords != null ? attemptRecords.size() : 1;
        List<PlanNodeAttempt> attempts = toPlanAttempts(attemptRecords);
        if (!result.success()) {
            String err = result.safeOutputs().getOrDefault("error", "节点执行失败");
            String summary = formatFailureSummary(err, attemptCount);
            runSession.noteNodeFailure(nodeId);
            wfCtx.putNodeFailure(nodeId, err, attemptCount);
            recordNodeTrace(streamCtx, nodeId, rawSpec.type(), "failed",
                    summary, null, startedAt, endedAt, attemptCount, retryPolicy.onFailure(), attempts);
            applyOnFailure(retryPolicy.onFailure(), nodeId, err, wfCtx, runSession);
            if (showTimeline) {
                return Flux.fromIterable(WorkflowNodeTimeline.complete(
                        session, nodeId, rawSpec.type(), summary, startedAt, endedAt));
            }
            return Flux.empty();
        }
        Map<String, String> outs = result.safeOutputs();
        if ("answer".equals(rawSpec.type())) {
            GroundingVerdict grounding = validateAnswerGrounding(outs, wfCtx);
            if (grounding != null && !grounding.passed()) {
                String err = grounding.reason();
                String summary = formatFailureSummary(err, attemptCount);
                runSession.noteNodeFailure(nodeId);
                wfCtx.putNodeFailure(nodeId, err, attemptCount);
                recordNodeTrace(streamCtx, nodeId, rawSpec.type(), "failed",
                        summary, null, startedAt, endedAt, attemptCount, retryPolicy.onFailure(), attempts);
                applyOnFailure(retryPolicy.onFailure(), nodeId, err, wfCtx, runSession);
                if (showTimeline) {
                    return Flux.fromIterable(WorkflowNodeTimeline.complete(
                            session, nodeId, rawSpec.type(), summary, startedAt, endedAt));
                }
                return Flux.empty();
            }
        }
        wfCtx.putNode(nodeId, outs);
        runSession.noteNodeSuccess(nodeId, outs);
        String summaryLine = resolveNodeDetail(rawSpec, outs);
        if (attemptCount > 1) {
            summaryLine = summaryLine + "（第 " + attemptCount + " 次尝试成功）";
        }
        String expandDetail = resolveExpandDetail(
                rawSpec, outs, summaryLine, streamCtx.assistantMsgId());
        recordNodeTrace(streamCtx, nodeId, rawSpec.type(), "completed",
                summaryLine, expandDetail, startedAt, endedAt, attemptCount, retryPolicy.onFailure(), attempts);
        if (showTimeline && isStreamingOutputNode(rawSpec.type())) {
            String answer = outs.getOrDefault("answer", outs.get("output"));
            if (StringUtils.hasText(answer)) {
                session.appendDelta(WorkflowNodeTimeline.stepId(nodeId), "result", answer.strip());
            }
        }
        List<StreamToken> all = new ArrayList<>(result.timelineTokens());
        all.addAll(result.contentTokens());
        if (showTimeline) {
            all.addAll(0, WorkflowNodeTimeline.complete(
                    session, nodeId, rawSpec.type(),
                    summaryLine, expandDetail, startedAt, endedAt));
        }
        return Flux.fromIterable(all);
    }

    private GroundingVerdict validateAnswerGrounding(Map<String, String> outs, WorkflowContext wfCtx) {
        if (!groundingProperties.isEnabled()) {
            return null;
        }
        String answer = outs.getOrDefault("answer", outs.get("output"));
        GroundingVerdict verdict = groundingChecker.check(
                answer, GroundingEvidenceSupport.fromWorkflow(wfCtx));
        if (verdict.passed()) {
            return null;
        }
        log.warn("[WorkflowExecutor] answer Grounding 未通过: {}", verdict.reason());
        if (!groundingProperties.isBlockOnFailure()) {
            return null;
        }
        return verdict;
    }

    private static void applyOnFailure(
            OnFailureAction action,
            String nodeId,
            String err,
            WorkflowContext wfCtx,
            WorkflowRunSession runSession) {
        switch (action) {
            case SKIP -> {
                Map<String, String> placeholder = new LinkedHashMap<>();
                placeholder.put("output", "");
                placeholder.put("degraded", "true");
                wfCtx.putNode(nodeId, placeholder);
            }
            case FAIL_FAST -> runSession.abort(OnFailureAction.FAIL_FAST, "节点 " + nodeId + " 失败: " + err);
            case FALLBACK_REACT -> runSession.abort(OnFailureAction.FALLBACK_REACT, "节点 " + nodeId + " 失败: " + err);
            default -> { }
        }
    }

    private static String formatFailureSummary(String err, int attemptCount) {
        String base = "失败: " + err;
        if (attemptCount > 1) {
            return base + "（已重试 " + attemptCount + " 次）";
        }
        return base;
    }

    private static List<PlanNodeAttempt> toPlanAttempts(List<NodeRetryExecutor.PlanNodeAttemptRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(r -> new PlanNodeAttempt(
                        r.attemptNo(), r.status(), r.errorClass(), r.summary(),
                        r.startedAt(), r.endedAt()))
                .collect(Collectors.toList());
    }

    private static String resolveNodeDetail(NodeSpec spec, Map<String, String> outputs) {
        if ("llm".equals(spec.type()) || "answer".equals(spec.type())) {
            return nodeDisplayName(spec) + "完成";
        }
        String detail = outputs.get("detail");
        if (detail != null && !detail.isBlank()) {
            return detail;
        }
        String hitCount = outputs.get("hitCount");
        if (hitCount != null && !hitCount.isBlank()) {
            return "命中 " + hitCount + " 条";
        }
        if ("agent".equals(spec.type())) {
            String summary = outputs.get("detail");
            if (summary != null && !summary.isBlank()) {
                return summary;
            }
        }
        return nodeDisplayName(spec) + "完成";
    }

    private static String nodeDisplayName(NodeSpec spec) {
        if (StringUtils.hasText(spec.displayName())) {
            return spec.displayName().strip();
        }
        return WorkflowNodeLabels.displayName(spec.id(), spec.type());
    }

    private static String resolveExpandDetail(
            NodeSpec spec, Map<String, String> outputs, String summaryLine, String traceMessageId) {
        if ("rag".equals(spec.type())) {
            String rewriteDetail = com.sunshine.orchestrator.rewrite.QueryRewriteTrace.combinedRagTimelineDetail(traceMessageId);
            if (rewriteDetail != null && !rewriteDetail.isBlank()) {
                return rewriteDetail;
            }
        }
        if ("agent".equals(spec.type())) {
            String expand = outputs.get("expandDetail");
            if (expand != null && !expand.isBlank()) {
                return expand.strip();
            }
            String answer = outputs.get("answer");
            if (answer != null && !answer.isBlank()) {
                return answer.strip();
            }
            return summaryLine;
        }
        if ("llm".equals(spec.type())) {
            String reasoning = outputs.get("reasoning");
            if (reasoning != null && !reasoning.isBlank()) {
                return reasoning.strip();
            }
            return null;
        }
        if ("answer".equals(spec.type())) {
            String reasoning = outputs.get("reasoning");
            if (reasoning != null && !reasoning.isBlank()) {
                return reasoning.strip();
            }
            String detail = outputs.get("detail");
            if (detail != null && !detail.isBlank()) {
                String answer = outputs.getOrDefault("answer", outputs.get("output"));
                if (answer != null && !answer.equals(detail)) {
                    return detail.strip();
                }
            }
            return null;
        }
        return summaryLine;
    }

    private static boolean isStreamingOutputNode(String type) {
        return "answer".equals(type) || "llm".equals(type);
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

    private void recordNodeTrace(
            ExecutionStreamContext streamCtx,
            String nodeId,
            String type,
            String status,
            String summary,
            String detail,
            long startedAt,
            long endedAt,
            int attemptCount,
            OnFailureAction onFailure,
            List<PlanNodeAttempt> attempts) {
        String planId = streamCtx.persistedPlanId();
        if (planId == null || planId.isBlank()) {
            return;
        }
        try {
            executionPlanStore.upsertNodeTrace(planId, new PlanNodeTrace(
                    nodeId, type, status, summary, detail, startedAt, endedAt,
                    attemptCount, onFailure.name(), attempts));
            if (attempts != null) {
                for (PlanNodeAttempt attempt : attempts) {
                    planExecutionAuditService.nodeAttempt(
                            streamCtx.conversationId(), streamCtx.assistantMsgId(),
                            streamCtx.userId(), streamCtx.tenantId(),
                            planId, nodeId, attempt, attemptCount);
                }
            }
        } catch (Exception e) {
            log.warn("[WorkflowExecutor] 写入 execution_trace 失败 planId={} node={}: {}",
                    planId, nodeId, e.getMessage());
        }
    }
}
