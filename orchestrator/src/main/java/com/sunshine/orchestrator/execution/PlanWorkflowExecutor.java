package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.AgentPauseProperties;
import com.sunshine.orchestrator.plan.ExecutionPlanEntity;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PlanApprovalService;
import com.sunshine.orchestrator.plan.PlanDisplayNameEnricher;
import com.sunshine.orchestrator.plan.PlanExecutionAuditService;
import com.sunshine.orchestrator.plan.PlanJson;
import com.sunshine.orchestrator.plan.PlanJsonParser;
import com.sunshine.orchestrator.plan.PlanMaterializer;
import com.sunshine.orchestrator.plan.PlanRunFinalizer;
import com.sunshine.orchestrator.plan.PlanValidator;
import com.sunshine.orchestrator.plan.WorkflowPlanner;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * plan-workflow 模式 — Planner 动态 DAG；规划失败 Replan；执行失败可降级 react。
 */
@Slf4j
@Component
public class PlanWorkflowExecutor {

    private final ExecutionPlanStore executionPlanStore;
    private final PlanExecutionAuditService planExecutionAuditService;
    private final WorkflowPauseService workflowPauseService;
    private final PlanWorkflowPlanningRunner planningRunner;
    private final PlanWorkflowResumeRunner resumeRunner;

    public PlanWorkflowExecutor(
            WorkflowPlanner workflowPlanner,
            PlanValidator planValidator,
            PlanDisplayNameEnricher displayNameEnricher,
            PlanMaterializer planMaterializer,
            WorkflowExecutor workflowExecutor,
            ReactExecutor reactExecutor,
            ExecutionPlanStore executionPlanStore,
            AgentExecutionProperties executionProperties,
            PlanExecutionAuditService planExecutionAuditService,
            PlanRunFinalizer planRunFinalizer,
            PlanJsonParser planJsonParser,
            PlanApprovalService planApprovalService,
            AgentPauseProperties pauseProperties,
            WorkflowPauseService workflowPauseService) {
        this.executionPlanStore = executionPlanStore;
        this.planExecutionAuditService = planExecutionAuditService;
        this.workflowPauseService = workflowPauseService;
        this.planningRunner = new PlanWorkflowPlanningRunner(
                workflowPlanner, planValidator, displayNameEnricher, planMaterializer,
                workflowExecutor, reactExecutor, executionPlanStore, executionProperties,
                planExecutionAuditService, planRunFinalizer, planApprovalService);
        this.resumeRunner = new PlanWorkflowResumeRunner(
                executionPlanStore, displayNameEnricher, planJsonParser, planMaterializer,
                workflowExecutor, planRunFinalizer, planExecutionAuditService, pauseProperties,
                planningRunner);
    }

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        ProcessingTimelineSession session = newBoundSession(ctx);
        return Mono.fromCallable(() -> executionPlanStore.createDraft(ctx, emptyPlan()))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(planId -> planExecutionAuditService.created(
                        ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(), planId))
                .flatMapMany(planId -> {
                    workflowPauseService.bindRun(ctx.assistantMsgId(), planId);
                    return planningRunner.planAndExecute(ctx, planId, 1, null, session);
                })
                .onErrorResume(e -> {
                    log.warn("[PlanWorkflowExecutor] Planner 失败，降级 react: {}", e.getMessage());
                    return planningRunner.reactWithPlanFallback(ctx, "Planner 未产出有效 DAG：" + e.getMessage());
                });
    }

    /** 用户「继续生成」— 从 PAUSED 检查点续跑 DAG */
    public Flux<StreamToken> resumePaused(ExecutionStreamContext ctx, ExecutionPlanEntity entity) {
        return resumeRunner.resumePaused(ctx, entity);
    }

    private static ProcessingTimelineSession newBoundSession(ExecutionStreamContext ctx) {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(ctx.userContent());
        session.bindTraceMessageId(ctx.assistantMsgId());
        return session;
    }

    private static PlanJson emptyPlan() {
        return new PlanJson(null, "", List.of(), List.of());
    }
}
