package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.execution.retry.WorkflowRunSession;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PlanApprovalRejectedException;
import com.sunshine.orchestrator.plan.PlanApprovalRound;
import com.sunshine.orchestrator.plan.PlanApprovalService;
import com.sunshine.orchestrator.plan.PlanApprovalUserAction;
import com.sunshine.orchestrator.plan.PlanApprovalWaitResult;
import com.sunshine.orchestrator.plan.PlanDisplayNameEnricher;
import com.sunshine.orchestrator.plan.PlanExecutionAuditService;
import com.sunshine.orchestrator.plan.PlanJson;
import com.sunshine.orchestrator.plan.PlanMaterializer;
import com.sunshine.orchestrator.plan.PlanNormalizer;
import com.sunshine.orchestrator.plan.PlanRunFinalizer;
import com.sunshine.orchestrator.plan.PlanTimeline;
import com.sunshine.orchestrator.plan.PlanValidator;
import com.sunshine.orchestrator.plan.PlanValidationCode;
import com.sunshine.orchestrator.plan.PlanValidationIssue;
import com.sunshine.orchestrator.plan.PlanWorkflowPausedException;
import com.sunshine.orchestrator.plan.PlannerAttempt;
import com.sunshine.orchestrator.plan.WorkflowPlanner;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

/** Planner → 校验 → 用户确认 → 动态 DAG 执行 */
@Slf4j
@RequiredArgsConstructor
final class PlanWorkflowPlanningRunner {

    private final WorkflowPlanner workflowPlanner;
    private final PlanValidator planValidator;
    private final PlanDisplayNameEnricher displayNameEnricher;
    private final PlanMaterializer planMaterializer;
    private final WorkflowExecutor workflowExecutor;
    private final ReactExecutor reactExecutor;
    private final ExecutionPlanStore executionPlanStore;
    private final AgentExecutionProperties executionProperties;
    private final PlanExecutionAuditService planExecutionAuditService;
    private final PlanRunFinalizer planRunFinalizer;
    private final PlanApprovalService planApprovalService;

    Flux<StreamToken> planAndExecute(
            ExecutionStreamContext ctx,
            String persistedPlanId,
            int planAttempt,
            PlanValidationIssue lastValidationIssue,
            ProcessingTimelineSession session) {
        long startedAt = System.currentTimeMillis();
        List<StreamToken> prelude;
        if (planAttempt == 1 && !session.hasStep("plan")) {
            prelude = PlanTimeline.beginPlanning(session, startedAt);
        } else if (planAttempt > 1) {
            prelude = ProcessingTimelineSupport.run(session, () -> session.progress("plan",
                    lastValidationIssue != null
                            ? "校验未通过，正在重新规划"
                            : "正在重新规划"));
        } else {
            prelude = List.of();
        }
        Mono<PlanJson> plannerMono = planAttempt == 1
                ? workflowPlanner.plan(ctx)
                : workflowPlanner.replan(ctx, lastValidationIssue, planAttempt);
        // materialize：仅规划期错误走 Replan/降级；执行期错误不得伪装成 Planner 失败
        Mono<PlanJson> planned = plannerMono.flatMap(plannerJson -> {
            recordPlannerAttempt(ctx, persistedPlanId, planAttempt, "plan", "completed", null, startedAt);
            return Mono.fromRunnable(() -> executionPlanStore.updatePlannerOutput(persistedPlanId, plannerJson))
                    .subscribeOn(Schedulers.boundedElastic())
                    .thenReturn(plannerJson);
        });
        return Flux.concat(
                Flux.fromIterable(prelude),
                planned.materialize().flatMapMany(signal -> {
                    if (signal.isOnError()) {
                        Throwable e = signal.getThrowable();
                        String msg = e != null ? e.getMessage() : "unknown";
                        recordPlannerAttempt(ctx, persistedPlanId, planAttempt, "plan", "failed", msg, startedAt);
                        int maxReplan = Math.max(1, executionProperties.getPlanWorkflow().getReplan().getMaxAttempts());
                        if (planAttempt < maxReplan) {
                            log.warn("[PlanWorkflowExecutor] Planner 第 {} 次失败，将 Replan: {}", planAttempt, msg);
                            return planAndExecute(ctx, persistedPlanId, planAttempt + 1,
                                    PlanValidationIssue.of(PlanValidationCode.VALIDATION_FAILED, msg),
                                    session);
                        }
                        return Mono.fromRunnable(() -> executionPlanStore.markRejected(persistedPlanId, msg))
                                .subscribeOn(Schedulers.boundedElastic())
                                .doOnSuccess(v -> planExecutionAuditService.failed(
                                        ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(),
                                        persistedPlanId, msg))
                                .thenMany(reactWithPlanFallback(ctx, "Planner 失败：" + msg));
                    }
                    if (signal.isOnNext()) {
                        return executePlanned(ctx, persistedPlanId, signal.get(), planAttempt, session)
                                .onErrorResume(ex -> handlePlanExecutionError(ctx, persistedPlanId, session, ex));
                    }
                    return Flux.empty();
                })
        );
    }

    /** 执行期异常：落库已 failed；时间线标明失败，禁止静默改 ReAct */
    private Flux<StreamToken> handlePlanExecutionError(
            ExecutionStreamContext ctx,
            String planId,
            ProcessingTimelineSession session,
            Throwable e) {
        String msg = e != null && StringUtils.hasText(e.getMessage()) ? e.getMessage() : "未知错误";
        log.error("[PlanWorkflowExecutor] Plan 执行失败 planId={}: {}", planId, msg);
        List<StreamToken> tokens = ProcessingTimelineSupport.run(session, () -> {
            if (session.hasStep("plan")) {
                session.completeAt("plan", "执行失败：" + msg, System.currentTimeMillis());
            }
        });
        return Flux.fromIterable(tokens);
    }

    Flux<StreamToken> executePlanned(
            ExecutionStreamContext ctx,
            String persistedPlanId,
            PlanJson plannerJson,
            int planAttempt,
            ProcessingTimelineSession session) {
        return Mono.fromCallable(() -> persistedPlanId)
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(planId -> {
                    session.bindUserQuery(ctx.userContent());
                    session.bindTraceMessageId(ctx.assistantMsgId());
                    PlanValidationIssue plannerError = planValidator.validatePlannerOutput(plannerJson);
                    if (plannerError != null) {
                        return handleValidationFailure(ctx, planId, session, plannerError, planAttempt);
                    }
                    PlanJson normalized = PlanNormalizer.normalize(plannerJson);
                    PlanJson enriched = displayNameEnricher.enrich(normalized);
                    PlanValidationIssue validationError = planValidator.validate(enriched);
                    if (validationError != null) {
                        return handleValidationFailure(ctx, planId, session, validationError, planAttempt);
                    }
                    if (planApprovalService.isEnabled()) {
                        try {
                            List<StreamToken> approvalTokens = new ArrayList<>();
                            PlanJson approved = runUserApprovalLoop(
                                    ctx, planId, session, enriched, approvalTokens);
                            return Flux.concat(
                                    Flux.fromIterable(approvalTokens),
                                    runValidatedPlan(ctx, planId, session, approved, planAttempt, true));
                        } catch (PlanWorkflowPausedException e) {
                            return Flux.empty();
                        } catch (PlanApprovalRejectedException e) {
                            return handleApprovalFailure(ctx, planId, session, e.getMessage());
                        }
                    }
                    return runValidatedPlan(ctx, planId, session, enriched, planAttempt, false);
                });
    }

    Flux<StreamToken> reactWithPlanFallback(ExecutionStreamContext ctx, String planSummary) {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(ctx.userContent());
        session.bindTraceMessageId(ctx.assistantMsgId());
        List<StreamToken> fallbackPlan = PlanTimeline.planFallbackStep(
                session, planSummary + "；改由自主智能体执行");
        return Flux.concat(Flux.fromIterable(fallbackPlan), reactExecutor.execute(ctx));
    }

    private PlanJson runUserApprovalLoop(
            ExecutionStreamContext ctx,
            String planId,
            ProcessingTimelineSession session,
            PlanJson enriched,
            List<StreamToken> approvalTokens) {
        PlanJson current = enriched;
        List<PlanApprovalRound> rounds = new ArrayList<>();
        int roundNo = 1;
        int maxRounds = Math.max(1, executionProperties.getPlanWorkflow().getApproval().getMaxUserRounds());
        while (true) {
            if (roundNo > maxRounds) {
                throw new PlanApprovalRejectedException("超过最大重新生成次数");
            }
            PlanApprovalWaitResult wait = planApprovalService.awaitUserApproval(
                    ctx, planId, current, session, rounds, roundNo);
            approvalTokens.addAll(wait.tokens());
            if (wait.action() == PlanApprovalUserAction.CANCELLED) {
                throw new PlanWorkflowPausedException();
            }
            rounds = new ArrayList<>(executionPlanStore.listApprovalRounds(planId));
            if (wait.action() == PlanApprovalUserAction.APPROVED) {
                return current;
            }
            if (wait.action() == PlanApprovalUserAction.TIMED_OUT) {
                throw new PlanApprovalRejectedException("用户未在时限内确认执行计划");
            }
            roundNo++;
            current = workflowPlanner.replanWithUserHint(ctx, wait.modificationHint(), roundNo).block();
            if (current == null) {
                throw new PlanApprovalRejectedException("重新规划未产出有效 Plan");
            }
            PlanValidationIssue plannerError = planValidator.validatePlannerOutput(current);
            if (plannerError != null) {
                throw new PlanApprovalRejectedException("重新规划未通过校验：" + plannerError.message());
            }
            current = displayNameEnricher.enrich(PlanNormalizer.normalize(current));
            PlanValidationIssue validationError = planValidator.validate(current);
            if (validationError != null) {
                throw new PlanApprovalRejectedException("重新规划未通过校验：" + validationError.message());
            }
            executionPlanStore.updatePlannerOutput(planId, current);
        }
    }

    private Flux<StreamToken> handleApprovalFailure(
            ExecutionStreamContext ctx,
            String planId,
            ProcessingTimelineSession session,
            String reason) {
        List<StreamToken> planTokens = PlanTimeline.planRejectedStep(session, reason);
        return Mono.fromRunnable(() -> executionPlanStore.markRejected(planId, reason))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(v -> planExecutionAuditService.failed(
                        ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(),
                        planId, reason))
                .thenMany(Flux.concat(
                        Flux.fromIterable(planTokens),
                        reactExecutor.execute(ctx)));
    }

    private Flux<StreamToken> handleValidationFailure(
            ExecutionStreamContext ctx,
            String planId,
            ProcessingTimelineSession session,
            PlanValidationIssue validationIssue,
            int planAttempt) {
        log.warn("[PlanWorkflowExecutor] Plan 校验失败(attempt={}): {}", planAttempt, validationIssue.message());
        recordPlannerAttempt(ctx, planId, planAttempt, "validate", "failed", validationIssue.message(), System.currentTimeMillis());
        int maxReplan = Math.max(1, executionProperties.getPlanWorkflow().getReplan().getMaxAttempts());
        if (planAttempt < maxReplan) {
            return planAndExecute(ctx, planId, planAttempt + 1, validationIssue, session);
        }
        List<StreamToken> planTokens = PlanTimeline.planRejectedStep(session, validationIssue.message());
        return Mono.fromRunnable(() -> executionPlanStore.markRejected(planId, validationIssue.message()))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(v -> planExecutionAuditService.failed(
                        ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(),
                        planId, validationIssue.message()))
                .thenMany(Flux.concat(
                        Flux.fromIterable(planTokens),
                        reactExecutor.execute(ctx)));
    }

    Flux<StreamToken> runValidatedPlan(
            ExecutionStreamContext ctx,
            String planId,
            ProcessingTimelineSession session,
            PlanJson enriched,
            int planAttempt,
            boolean afterUserApproval) {
        List<StreamToken> planTokens;
        if (afterUserApproval) {
            String chain = PlanTimeline.planChainSummary(enriched);
            String detail = PlanTimeline.formatPlanDetail(planId, chain, 0);
            planTokens = ProcessingTimelineSupport.run(session, () ->
                    session.completePlanAt(chain, detail, System.currentTimeMillis()));
        } else {
            planTokens = PlanTimeline.finishPlanStep(session, enriched, planId, planAttempt - 1);
        }
        WorkflowDefinition def = planMaterializer.materialize(enriched);
        log.info("[PlanWorkflowExecutor] 执行动态 Plan: {} 节点链={}",
                def.id(), PlanTimeline.planChainSummary(enriched));
        ExecutionStreamContext execCtx = ctx.withPersistedPlanId(planId);
        WorkflowRunSession runSession = new WorkflowRunSession();
        int nodeCount = enriched.nodes().size();
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
                        workflowExecutor.executeDynamicDefinition(def, execCtx, runSession)
                                .concatWith(Flux.defer(() -> planRunFinalizer.postWorkflow(ctx, planId, runSession)))
                                .doOnError(err -> {
                                    executionPlanStore.markFailed(planId, err.getMessage());
                                    planExecutionAuditService.failed(
                                            ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(),
                                            ctx.tenantId(), planId, err.getMessage());
                                })
                ));
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
