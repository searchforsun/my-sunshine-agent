package com.sunshine.orchestrator.plan;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.ReactExecutor;
import com.sunshine.orchestrator.execution.retry.WorkflowRunSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Plan 工作流执行终态：状态落库、审计与可选 ReAct 降级 */
@Component
@RequiredArgsConstructor
public class PlanRunFinalizer {

    private final ExecutionPlanStore executionPlanStore;
    private final PlanExecutionAuditService planExecutionAuditService;
    private final AgentExecutionProperties executionProperties;
    private final ReactExecutor reactExecutor;

    public Flux<StreamToken> postWorkflow(
            ExecutionStreamContext ctx,
            String planId,
            WorkflowRunSession runSession) {
        if (runSession.isFallbackReact()
                && executionProperties.getPlanWorkflow().getFallbackReact().isEnabled()) {
            return Mono.fromRunnable(() -> executionPlanStore.markDegradedReact(
                            planId, runSession.getAbortReason()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnSuccess(v -> {
                        planExecutionAuditService.fallbackReact(
                                ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(),
                                planId, runSession.getAbortReason());
                        planExecutionAuditService.completed(
                                ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(),
                                planId, "degraded_react");
                    })
                    .thenMany(reactWithPartialContext(ctx, runSession, runSession.getAbortReason()));
        }
        return Mono.fromRunnable(() -> finalizePlanStatus(planId, runSession))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(v -> emitPlanTerminalAudit(ctx, planId, runSession))
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

    private void emitPlanTerminalAudit(ExecutionStreamContext ctx, String planId, WorkflowRunSession runSession) {
        if (runSession.isFallbackReact()) {
            return;
        }
        if (runSession.isFailFast()) {
            planExecutionAuditService.failed(
                    ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(),
                    planId, runSession.getAbortReason());
            return;
        }
        String terminalStatus = runSession.isHasNodeFailures() ? "completed_with_errors" : "completed";
        planExecutionAuditService.completed(
                ctx.conversationId(), ctx.assistantMsgId(), ctx.userId(), ctx.tenantId(),
                planId, terminalStatus);
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
}
