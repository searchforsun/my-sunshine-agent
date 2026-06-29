package com.sunshine.orchestrator.execution.workflow;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.WorkflowDefinition;
import com.sunshine.orchestrator.execution.WorkflowDefinitionLoader;
import com.sunshine.orchestrator.execution.retry.WorkflowRunSession;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PlanDisplayNameEnricher;
import com.sunshine.orchestrator.plan.PlanExecutionAuditService;
import com.sunshine.orchestrator.plan.PlanJson;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;

/** 静态 Nacos workflow → 物化 Plan → 委托 DAG 执行 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowStaticPlanRunner {

    private final WorkflowDefinitionLoader loader;
    private final ExecutionPlanStore executionPlanStore;
    private final PlanExecutionAuditService planExecutionAuditService;
    private final PlanDisplayNameEnricher displayNameEnricher;
    private final PlanRunFinalizer planRunFinalizer;

    @FunctionalInterface
    public interface DagExecutor {
        Flux<StreamToken> executeDynamicDefinition(
                WorkflowDefinition def,
                ExecutionStreamContext streamCtx,
                WorkflowRunSession runSession);
    }

    public Flux<StreamToken> execute(ExecutionStreamContext ctx, DagExecutor dagExecutor) {
        ExecutionPlan plan = ctx.plan();
        if (plan == null || plan.mode() != ExecutionMode.WORKFLOW) {
            return Flux.just(StreamToken.content("内部错误：WorkflowExecutor 收到非 workflow 计划"));
        }
        String workflowId = plan.workflowId();
        Optional<WorkflowDefinition> defOpt = loader.load(workflowId);
        if (defOpt.isEmpty()) {
            log.error("[WorkflowStaticPlanRunner] 未找到 workflow 定义: {}", workflowId);
            return Flux.just(StreamToken.content(
                    "工作流「" + workflowId + "」未定义，请联系管理员。"));
        }
        WorkflowDefinition def = defOpt.get();
        PlanJson rawPlan = StaticPlanAdapter.from(def, plan.reason());
        return Mono.fromCallable(() -> executionPlanStore.createDraft(ctx, rawPlan))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(planId -> planExecutionAuditService.created(
                        ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(), planId))
                .flatMapMany(planId -> runStaticAsPlan(ctx, planId, def, rawPlan, dagExecutor));
    }

    private Flux<StreamToken> runStaticAsPlan(
            ExecutionStreamContext ctx,
            String planId,
            WorkflowDefinition def,
            PlanJson rawPlan,
            DagExecutor dagExecutor) {
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(ctx.userContent());
        session.bindTraceMessageId(ctx.assistantMsgId());
        PlanJson enriched = displayNameEnricher.enrich(rawPlan);
        List<StreamToken> planTokens = PlanTimeline.planStep(session, enriched, planId);
        ExecutionStreamContext execCtx = ctx.withPersistedPlanId(planId);
        WorkflowRunSession runSession = new WorkflowRunSession();
        int nodeCount = enriched.nodes().size();
        log.info("[WorkflowStaticPlanRunner] 静态工作流 {} 物化为 Plan id={} 链={}",
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
                        dagExecutor.executeDynamicDefinition(def, execCtx, runSession)
                                .concatWith(Flux.defer(() -> planRunFinalizer.postWorkflow(ctx, planId, runSession)))
                                .doOnError(err -> {
                                    executionPlanStore.markFailed(planId, err.getMessage());
                                    planExecutionAuditService.failed(
                                            ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(),
                                            ctx.tenantId(), planId, err.getMessage());
                                })
                ));
    }
}
