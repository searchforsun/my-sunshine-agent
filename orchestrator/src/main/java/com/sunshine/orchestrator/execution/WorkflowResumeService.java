package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentPauseProperties;
import com.sunshine.orchestrator.execution.retry.WorkflowRunSession;
import com.sunshine.orchestrator.plan.ExecutionPlanEntity;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PausePhase;
import com.sunshine.orchestrator.plan.PlanExecutionAuditService;
import com.sunshine.orchestrator.plan.PlanJson;
import com.sunshine.orchestrator.plan.PlanJsonParser;
import com.sunshine.orchestrator.plan.PlanMaterializer;
import com.sunshine.orchestrator.plan.PlanNormalizer;
import com.sunshine.orchestrator.plan.PlanRunFinalizer;
import com.sunshine.orchestrator.plan.ResumeInteractionHint;
import com.sunshine.orchestrator.plan.WorkflowCheckpoint;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 静态 Workflow 暂停续跑（EXECUTING 检查点；旧动态规划已下线） */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowResumeService {

    private final ExecutionPlanStore executionPlanStore;
    private final PlanJsonParser planJsonParser;
    private final PlanMaterializer planMaterializer;
    private final WorkflowExecutor workflowExecutor;
    private final PlanRunFinalizer planRunFinalizer;
    private final PlanExecutionAuditService planExecutionAuditService;
    private final AgentPauseProperties pauseProperties;

    public Flux<StreamToken> resumePaused(ExecutionStreamContext ctx, ExecutionPlanEntity entity) {
        WorkflowCheckpoint checkpoint = executionPlanStore.loadCheckpoint(entity);
        String planId = entity.getId();
        if (checkpoint.pausePhase() == PausePhase.PLANNING) {
            log.warn("[WorkflowResume] 忽略 PLANNING 阶段残留检查点 planId={}", planId);
            return Flux.just(StreamToken.content("该任务已下线（旧动态规划），请重新发送问题。"));
        }
        ExecutionStreamContext execCtx = ctx.withPersistedPlanId(planId);
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
        ExecutionStreamContext resumeCtx = execCtx;
        if (effectiveCheckpoint.pendingInteraction() != null && pauseProperties.isResumeInteractionEnabled()) {
            resumeCtx = execCtx.withResumeInteraction(
                    new ResumeInteractionHint(effectiveCheckpoint.pendingInteraction()));
        }
        WorkflowCheckpoint resumeCheckpoint = effectiveCheckpoint;
        log.info("[WorkflowResume] 续跑 Workflow planId={} fromNode={}", planId, resumeCheckpoint.resumeNodeId());
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
}
