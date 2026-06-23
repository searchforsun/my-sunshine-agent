package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PlanJson;
import com.sunshine.orchestrator.plan.PlanDisplayNameEnricher;
import com.sunshine.orchestrator.plan.PlanMaterializer;
import com.sunshine.orchestrator.plan.PlanNormalizer;
import com.sunshine.orchestrator.plan.PlanTimeline;
import com.sunshine.orchestrator.plan.PlanValidator;
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

import java.util.List;

/**
 * plan-workflow 模式 — Planner 动态 DAG；规划失败时展示 plan 步说明后降级 react。
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

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        return workflowPlanner.plan(ctx)
                .map(PlanNormalizer::normalize)
                .flatMapMany(planJson -> executePlanned(ctx, planJson))
                .onErrorResume(e -> {
                    log.warn("[PlanWorkflowExecutor] Planner 失败，降级 react: {}", e.getMessage());
                    return reactWithPlanFallback(ctx, "Planner 未产出有效 DAG：" + e.getMessage());
                });
    }

    private Flux<StreamToken> executePlanned(ExecutionStreamContext ctx, PlanJson planJson) {
        return Mono.fromCallable(() -> executionPlanStore.createDraft(ctx, planJson))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(persistedPlanId -> {
                    ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
                    session.bindUserQuery(ctx.userContent());
                    session.bindTraceMessageId(ctx.assistantMsgId());
                    PlanJson enriched = displayNameEnricher.enrich(planJson);
                    List<StreamToken> planTokens = PlanTimeline.planStep(session, enriched, persistedPlanId);

                    String validationError = planValidator.validate(enriched);
                    if (StringUtils.hasText(validationError)) {
                        log.warn("[PlanWorkflowExecutor] Plan 校验失败: {}，降级 react", validationError);
                        return Mono.fromRunnable(() -> executionPlanStore.markRejected(persistedPlanId, validationError))
                                .subscribeOn(Schedulers.boundedElastic())
                                .thenMany(Flux.concat(
                                        Flux.fromIterable(planTokens),
                                        reactExecutor.execute(ctx)));
                    }

                    WorkflowDefinition def = planMaterializer.materialize(enriched);
                    log.info("[PlanWorkflowExecutor] 执行动态 Plan: {} 节点链={}",
                            def.id(), PlanTimeline.planChainSummary(enriched));
                    ExecutionStreamContext execCtx = ctx.withPersistedPlanId(persistedPlanId);
                    return Mono.fromRunnable(() -> {
                                executionPlanStore.markValidated(persistedPlanId, enriched);
                                executionPlanStore.markRunning(persistedPlanId);
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .thenMany(Flux.concat(
                                    Flux.fromIterable(planTokens),
                                    workflowExecutor.executeDynamicDefinition(def, execCtx)
                                            .publishOn(Schedulers.boundedElastic())
                                            .doOnComplete(() -> executionPlanStore.markCompleted(persistedPlanId))
                                            .doOnError(err -> executionPlanStore.markFailed(
                                                    persistedPlanId, err.getMessage()))
                            ));
                });
    }

    private Flux<StreamToken> reactWithPlanFallback(ExecutionStreamContext ctx, String planSummary) {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(ctx.userContent());
        session.bindTraceMessageId(ctx.assistantMsgId());
        List<StreamToken> fallbackPlan = PlanTimeline.planFallbackStep(
                session, planSummary + "；改由自主智能体执行");
        return Flux.concat(Flux.fromIterable(fallbackPlan), reactExecutor.execute(ctx));
    }
}
