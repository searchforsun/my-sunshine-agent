package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.execution.retry.WorkflowRunSession;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PlanJson;
import com.sunshine.orchestrator.plan.PlanDisplayNameEnricher;
import com.sunshine.orchestrator.plan.PlanMaterializer;
import com.sunshine.orchestrator.plan.PlanNormalizer;
import com.sunshine.orchestrator.plan.PlanTimeline;
import com.sunshine.orchestrator.plan.PlanValidator;
import com.sunshine.orchestrator.plan.PlannerAttempt;
import com.sunshine.orchestrator.plan.PlanExecutionAuditService;
import com.sunshine.orchestrator.plan.WorkflowPlanner;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * plan-workflow 模式 — Planner 动态 DAG；规划失败 Replan；执行失败可降级 react。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanWorkflowExecutor {

    private final WorkflowPlanner workflowPlanner;
    private final PlanValidator planValidator;
    private final PlanDisplayNameEnricher displayNameEnricher;
    private final PlanMaterializer planMaterializer;
    private final WorkflowExecutor workflowExecutor;
    private final ReactExecutor reactExecutor;
    private final ExecutionPlanStore executionPlanStore;
    private final AgentExecutionProperties executionProperties;
    private final PlanExecutionAuditService planExecutionAuditService;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        return Mono.fromCallable(() -> executionPlanStore.createDraft(ctx, emptyPlan()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(planId -> planAndExecute(ctx, planId, 1, null))
                .onErrorResume(e -> {
                    log.warn("[PlanWorkflowExecutor] Planner 失败，降级 react: {}", e.getMessage());
                    return reactWithPlanFallback(ctx, "Planner 未产出有效 DAG：" + e.getMessage());
                });
    }

    private static PlanJson emptyPlan() {
        return new PlanJson(null, "", List.of(), List.of());
    }

    private Flux<StreamToken> planAndExecute(
            ExecutionStreamContext ctx,
            String persistedPlanId,
            int planAttempt,
            String lastValidationError) {
        long startedAt = System.currentTimeMillis();
        Mono<PlanJson> plannerMono = planAttempt == 1
                ? workflowPlanner.plan(ctx)
                : workflowPlanner.replan(ctx, lastValidationError, planAttempt);
        return plannerMono
                .flatMapMany(plannerJson -> {
                    recordPlannerAttempt(ctx, persistedPlanId, planAttempt, "plan", "completed", null, startedAt);
                    return Mono.fromRunnable(() -> executionPlanStore.updatePlannerOutput(persistedPlanId, plannerJson))
                            .subscribeOn(Schedulers.boundedElastic())
                            .thenMany(executePlanned(ctx, persistedPlanId, plannerJson, planAttempt));
                })
                .onErrorResume(e -> {
                    recordPlannerAttempt(ctx, persistedPlanId, planAttempt, "plan", "failed",
                            e.getMessage(), startedAt);
                    int maxReplan = Math.max(1, executionProperties.getPlanWorkflow().getReplan().getMaxAttempts());
                    if (planAttempt < maxReplan) {
                        log.warn("[PlanWorkflowExecutor] Planner 第 {} 次失败，将 Replan: {}", planAttempt, e.getMessage());
                        return planAndExecute(ctx, persistedPlanId, planAttempt + 1, e.getMessage());
                    }
                    return Mono.fromRunnable(() -> executionPlanStore.markRejected(persistedPlanId, e.getMessage()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .thenMany(reactWithPlanFallback(ctx, "Planner 失败：" + e.getMessage()));
                });
    }

    private Flux<StreamToken> executePlanned(
            ExecutionStreamContext ctx,
            String persistedPlanId,
            PlanJson plannerJson,
            int planAttempt) {
        return Mono.fromCallable(() -> persistedPlanId)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(planId -> {
                    ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
                    session.bindUserQuery(ctx.userContent());
                    session.bindTraceMessageId(ctx.assistantMsgId());
                    String plannerError = planValidator.validatePlannerOutput(plannerJson);
                    if (StringUtils.hasText(plannerError)) {
                        return handleValidationFailure(ctx, planId, session, plannerError, planAttempt);
                    }
                    PlanJson normalized = PlanNormalizer.normalize(plannerJson);
                    PlanJson enriched = displayNameEnricher.enrich(normalized);
                    String validationError = planValidator.validate(enriched);
                    if (StringUtils.hasText(validationError)) {
                        return handleValidationFailure(ctx, planId, session, validationError, planAttempt);
                    }
                    return runValidatedPlan(ctx, planId, session, enriched, planAttempt);
                });
    }

    private Flux<StreamToken> handleValidationFailure(
            ExecutionStreamContext ctx,
            String planId,
            ProcessingTimelineSession session,
            String validationError,
            int planAttempt) {
        log.warn("[PlanWorkflowExecutor] Plan 校验失败(attempt={}): {}", planAttempt, validationError);
        recordPlannerAttempt(ctx, planId, planAttempt, "validate", "failed", validationError, System.currentTimeMillis());
        int maxReplan = Math.max(1, executionProperties.getPlanWorkflow().getReplan().getMaxAttempts());
        if (planAttempt < maxReplan) {
            return planAndExecute(ctx, planId, planAttempt + 1, validationError);
        }
        List<StreamToken> planTokens = PlanTimeline.planRejectedStep(session, validationError);
        return Mono.fromRunnable(() -> executionPlanStore.markRejected(planId, validationError))
                .subscribeOn(Schedulers.boundedElastic())
                .thenMany(Flux.concat(
                        Flux.fromIterable(planTokens),
                        reactExecutor.execute(ctx)));
    }

    private Flux<StreamToken> runValidatedPlan(
            ExecutionStreamContext ctx,
            String planId,
            ProcessingTimelineSession session,
            PlanJson enriched,
            int planAttempt) {
        List<StreamToken> planTokens = PlanTimeline.planStep(session, enriched, planId, planAttempt - 1);
        WorkflowDefinition def = planMaterializer.materialize(enriched);
        log.info("[PlanWorkflowExecutor] 执行动态 Plan: {} 节点链={}",
                def.id(), PlanTimeline.planChainSummary(enriched));
        ExecutionStreamContext execCtx = ctx.withPersistedPlanId(planId);
        WorkflowRunSession runSession = new WorkflowRunSession();
        return Mono.fromRunnable(() -> {
                    executionPlanStore.markValidated(planId, enriched);
                    executionPlanStore.markRunning(planId);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .thenMany(Flux.concat(
                        Flux.fromIterable(planTokens),
                        workflowExecutor.executeDynamicDefinition(def, execCtx, runSession)
                                .concatWith(Flux.defer(() -> postWorkflow(ctx, planId, runSession)))
                                .doOnError(err -> executionPlanStore.markFailed(planId, err.getMessage()))
                ));
    }

    private Flux<StreamToken> postWorkflow(
            ExecutionStreamContext ctx,
            String planId,
            WorkflowRunSession runSession) {
        if (runSession.isFallbackReact()
                && executionProperties.getPlanWorkflow().getFallbackReact().isEnabled()) {
            return Mono.fromRunnable(() -> executionPlanStore.markDegradedReact(
                            planId, runSession.getAbortReason()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnSuccess(v -> planExecutionAuditService.fallbackReact(
                            ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(),
                            planId, runSession.getAbortReason()))
                    .thenMany(reactWithPartialContext(ctx, runSession, runSession.getAbortReason()));
        }
        return Mono.fromRunnable(() -> finalizePlanStatus(planId, runSession))
                .subscribeOn(Schedulers.boundedElastic())
                .thenMany(Flux.empty());
    }

    private void finalizePlanStatus(String planId, WorkflowRunSession runSession) {
        if (runSession.isFailFast()) {
            executionPlanStore.markFailed(planId, runSession.getAbortReason());
            return;
        }
        if (runSession.isHasNodeFailures()) {
            executionPlanStore.markCompletedWithErrors(planId);
            return;
        }
        executionPlanStore.markCompleted(planId);
    }

    private Flux<StreamToken> reactWithPartialContext(
            ExecutionStreamContext ctx,
            WorkflowRunSession runSession,
            String reason) {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(ctx.userContent());
        session.bindTraceMessageId(ctx.assistantMsgId());
        List<StreamToken> fallbackPlan = PlanTimeline.planFallbackStep(
                session, reason + "；改由自主智能体接续执行");
        if (!executionProperties.getPlanWorkflow().getFallbackReact().isInjectPartialContext()) {
            return Flux.concat(Flux.fromIterable(fallbackPlan), reactExecutor.execute(ctx));
        }
        List<String> injected = buildPartialContext(runSession);
        return Flux.concat(
                Flux.fromIterable(fallbackPlan),
                reactExecutor.executeWithInjected(ctx, injected));
    }

    private static List<String> buildPartialContext(WorkflowRunSession runSession) {
        if (runSession.getPartialOutputs().isEmpty()) {
            return List.of();
        }
        List<String> blocks = new ArrayList<>();
        runSession.getPartialOutputs().forEach((nodeId, outputs) -> {
            String text = outputs.getOrDefault("output", outputs.getOrDefault("answer", ""));
            if (StringUtils.hasText(text)) {
                blocks.add("【节点 " + nodeId + "】\n" + text.strip());
            }
        });
        if (!runSession.getFailedNodeIds().isEmpty()) {
            blocks.add("【执行失败节点】" + String.join(", ", runSession.getFailedNodeIds()));
        }
        return blocks;
    }

    private Flux<StreamToken> reactWithPlanFallback(ExecutionStreamContext ctx, String planSummary) {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(ctx.userContent());
        session.bindTraceMessageId(ctx.assistantMsgId());
        List<StreamToken> fallbackPlan = PlanTimeline.planFallbackStep(
                session, planSummary + "；改由自主智能体执行");
        return Flux.concat(Flux.fromIterable(fallbackPlan), reactExecutor.execute(ctx));
    }

    private void recordPlannerAttempt(
            ExecutionStreamContext ctx,
            String planId,
            int attemptNo,
            String phase,
            String status,
            String error,
            long startedAt) {
        try {
            PlannerAttempt attempt = new PlannerAttempt(
                    attemptNo, phase, status, error, startedAt, System.currentTimeMillis());
            executionPlanStore.appendPlannerAttempt(planId, attempt);
            planExecutionAuditService.plannerAttempt(
                    ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(),
                    planId, attempt);
        } catch (Exception e) {
            log.warn("[PlanWorkflowExecutor] 记录 planner_attempt 失败: {}", e.getMessage());
        }
    }
}
