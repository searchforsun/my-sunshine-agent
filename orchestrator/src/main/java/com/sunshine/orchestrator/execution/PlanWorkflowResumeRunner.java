package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentPauseProperties;
import com.sunshine.orchestrator.execution.retry.WorkflowRunSession;
import com.sunshine.orchestrator.plan.ExecutionPlanEntity;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PausePhase;
import com.sunshine.orchestrator.plan.PlanDisplayNameEnricher;
import com.sunshine.orchestrator.plan.PlanExecutionAuditService;
import com.sunshine.orchestrator.plan.PlanJson;
import com.sunshine.orchestrator.plan.PlanJsonParser;
import com.sunshine.orchestrator.plan.PlanMaterializer;
import com.sunshine.orchestrator.plan.PlanNormalizer;
import com.sunshine.orchestrator.plan.PlanRunFinalizer;
import com.sunshine.orchestrator.plan.PlanTimeline;
import com.sunshine.orchestrator.plan.ResumeInteractionHint;
import com.sunshine.orchestrator.plan.WorkflowCheckpoint;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/** Plan-workflow 暂停续跑（PLANNING / EXECUTING 检查点） */
@Slf4j
@RequiredArgsConstructor
final class PlanWorkflowResumeRunner {

    private final ExecutionPlanStore executionPlanStore;
    private final PlanDisplayNameEnricher displayNameEnricher;
    private final PlanJsonParser planJsonParser;
    private final PlanMaterializer planMaterializer;
    private final WorkflowExecutor workflowExecutor;
    private final PlanRunFinalizer planRunFinalizer;
    private final PlanExecutionAuditService planExecutionAuditService;
    private final AgentPauseProperties pauseProperties;
    private final PlanWorkflowPlanningRunner planningRunner;

    Flux<StreamToken> resumePaused(ExecutionStreamContext ctx, ExecutionPlanEntity entity) {
        WorkflowCheckpoint checkpoint = executionPlanStore.loadCheckpoint(entity);
        String planId = entity.getId();
        ExecutionStreamContext execCtx = ctx.withPersistedPlanId(planId);
        if (checkpoint.pausePhase() == PausePhase.PLANNING) {
            log.info("[PlanWorkflowExecutor] 续跑 Plan id={} phase=PLANNING validated={}",
                    planId, StringUtils.hasText(entity.getValidatedJson()));
            return Mono.fromRunnable(() -> executionPlanStore.markResumed(planId))
                    .subscribeOn(Schedulers.boundedElastic())
                    .thenMany(Flux.defer(() -> {
                        if (StringUtils.hasText(entity.getValidatedJson())) {
                            PlanJson enriched = displayNameEnricher.enrich(
                                    PlanNormalizer.normalize(planJsonParser.parse(entity.getValidatedJson())));
                            return resumeValidatedPlanExecution(ctx, planId, enriched);
                        }
                        ProcessingTimelineSession session = newBoundSession(ctx);
                        return planningRunner.planAndExecute(ctx, planId, 1, null, session);
                    }));
        }
        PlanJson enriched = PlanNormalizer.normalize(planJsonParser.parse(entity.getValidatedJson()));
        WorkflowDefinition def = planMaterializer.materialize(enriched);
        WorkflowRunSession runSession = new WorkflowRunSession();
        WorkflowCheckpoint effectiveCheckpoint = checkpoint;
        if (checkpoint.pausePhase() == PausePhase.EXECUTING
                && !WorkflowContextCodec.hasNodes(checkpoint.wfCtxJson())) {
            WorkflowContext wfCtx = WorkflowContextCodec.fromJson(checkpoint.wfCtxJson());
            WorkflowContextResumeSupport.prepare(
                    wfCtx, execCtx, executionPlanStore.listNodeTraces(planId), def);
            String backfilled = WorkflowContextCodec.toJson(wfCtx);
            if (!WorkflowContextCodec.hasNodes(backfilled)) {
                return Flux.just(StreamToken.content("无法从检查点恢复执行上下文，请重新发送问题。"));
            }
            effectiveCheckpoint = new WorkflowCheckpoint(
                    checkpoint.resumeNodeId(), backfilled, checkpoint.pausePhase(), checkpoint.pendingInteraction());
        }
        log.info("[PlanWorkflowExecutor] 续跑 Plan id={} fromNode={}", planId, effectiveCheckpoint.resumeNodeId());
        WorkflowCheckpoint resumeCheckpoint = effectiveCheckpoint;
        ExecutionStreamContext resumeCtx = execCtx;
        if (resumeCheckpoint.pendingInteraction() != null && pauseProperties.isResumeInteractionEnabled()) {
            resumeCtx = execCtx.withResumeInteraction(new ResumeInteractionHint(resumeCheckpoint.pendingInteraction()));
        }
        return Mono.fromRunnable(() -> executionPlanStore.markResumed(planId))
                .subscribeOn(Schedulers.boundedElastic())
                .thenMany(Flux.concat(
                        workflowExecutor.resumeDynamicDefinition(def, resumeCtx, runSession, resumeCheckpoint)
                                .concatWith(Flux.defer(() -> planRunFinalizer.postWorkflow(ctx, planId, runSession)))
                                .doOnError(err -> {
                                    executionPlanStore.markPaused(planId, resumeCheckpoint);
                                    planExecutionAuditService.failed(
                                            ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(),
                                            ctx.tenantId(), planId, err.getMessage());
                                })
                ));
    }

    private Flux<StreamToken> resumeValidatedPlanExecution(
            ExecutionStreamContext ctx, String planId, PlanJson enriched) {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(ctx.userContent());
        session.bindTraceMessageId(ctx.assistantMsgId());
        List<StreamToken> planTokens = PlanTimeline.planStep(session, enriched, planId, 0);
        WorkflowDefinition def = planMaterializer.materialize(enriched);
        ExecutionStreamContext execCtx = ctx.withPersistedPlanId(planId);
        WorkflowRunSession runSession = new WorkflowRunSession();
        log.info("[PlanWorkflowExecutor] 续跑已校验 Plan: {} 节点链={}",
                def.id(), PlanTimeline.planChainSummary(enriched));
        return Flux.concat(
                Flux.fromIterable(planTokens),
                workflowExecutor.executeDynamicDefinition(def, execCtx, runSession)
                        .concatWith(Flux.defer(() -> planRunFinalizer.postWorkflow(ctx, planId, runSession)))
                        .doOnError(err -> {
                            executionPlanStore.markFailed(planId, err.getMessage());
                            planExecutionAuditService.failed(
                                    ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(),
                                    ctx.tenantId(), planId, err.getMessage());
                        })
        );
    }

    private static ProcessingTimelineSession newBoundSession(ExecutionStreamContext ctx) {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(ctx.userContent());
        session.bindTraceMessageId(ctx.assistantMsgId());
        return session;
    }
}
