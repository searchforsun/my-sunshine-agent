package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.retry.WorkflowRunSession;
import com.sunshine.orchestrator.execution.workflow.WorkflowNodeRunner;
import com.sunshine.orchestrator.execution.workflow.WorkflowStaticPlanRunner;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PausePhase;
import com.sunshine.orchestrator.plan.WorkflowCheckpoint;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Workflow DAG 线性执行引擎 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowExecutor {

    private final WorkflowStaticPlanRunner staticPlanRunner;
    private final WorkflowNodeRunner nodeRunner;
    private final ExecutionPlanStore executionPlanStore;
    private final WorkflowNodeLabelService labelService;
    private final WorkflowPauseService workflowPauseService;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        return staticPlanRunner.execute(ctx, this::executeDynamicDefinition);
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
        if (planWorkflow) {
            workflowPauseService.bindRun(streamCtx.assistantMsgId(), streamCtx.persistedPlanId());
            workflowPauseService.commitContext(streamCtx.assistantMsgId(), wfCtx);
        }
        return executeNodeOrder(def.linearOrder(), session, def, wfCtx, streamCtx, runSession, planWorkflow)
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> {
                    labelService.clearRuntimeNodeLabels();
                    if (planWorkflow) {
                        workflowPauseService.clearRun(streamCtx.assistantMsgId());
                    }
                });
    }

    /** 从暂停检查点续跑（跳过已完成节点） */
    public Flux<StreamToken> resumeDynamicDefinition(
            WorkflowDefinition def,
            ExecutionStreamContext streamCtx,
            WorkflowRunSession runSession,
            WorkflowCheckpoint checkpoint) {
        if (checkpoint.pausePhase() == PausePhase.PLANNING) {
            return Flux.empty();
        }
        labelService.bindRuntimeNodeLabels(def);
        WorkflowContext wfCtx = WorkflowContextCodec.fromJson(checkpoint.wfCtxJson());
        WorkflowContextResumeSupport.prepare(
                wfCtx,
                streamCtx,
                executionPlanStore.listNodeTraces(streamCtx.persistedPlanId()),
                def);
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(streamCtx.userContent());
        session.bindTraceMessageId(streamCtx.assistantMsgId());
        boolean planWorkflow = StringUtils.hasText(streamCtx.persistedPlanId());
        if (planWorkflow) {
            workflowPauseService.bindRun(streamCtx.assistantMsgId(), streamCtx.persistedPlanId());
            workflowPauseService.commitContext(streamCtx.assistantMsgId(), wfCtx);
        }
        List<String> order = def.linearOrder();
        int startIdx = order.indexOf(checkpoint.resumeNodeId());
        if (startIdx < 0) {
            startIdx = 0;
        }
        List<String> remaining = order.subList(startIdx, order.size());
        return executeNodeOrder(remaining, session, def, wfCtx, streamCtx, runSession, planWorkflow)
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> {
                    labelService.clearRuntimeNodeLabels();
                    if (planWorkflow) {
                        workflowPauseService.clearRun(streamCtx.assistantMsgId());
                    }
                });
    }

    private Flux<StreamToken> executeNodeOrder(
            List<String> nodeOrder,
            ProcessingTimelineSession session,
            WorkflowDefinition def,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            WorkflowRunSession runSession,
            boolean planWorkflow) {
        return Flux.fromIterable(nodeOrder)
                .concatMap(nodeId -> {
                    if (planWorkflow && workflowPauseService.consumePauseRequested(streamCtx.assistantMsgId())) {
                        return pauseBeforeNode(session, def, nodeId, wfCtx, streamCtx);
                    }
                    workflowPauseService.setCurrentNode(streamCtx.assistantMsgId(), nodeId);
                    return nodeRunner.executeNode(
                            session, def, nodeId, wfCtx, streamCtx, runSession, planWorkflow);
                });
    }

    private Flux<StreamToken> pauseBeforeNode(
            ProcessingTimelineSession session,
            WorkflowDefinition def,
            String resumeNodeId,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx) {
        String planId = streamCtx.persistedPlanId();
        String ctxJson = workflowPauseService.getCommittedContextJson(streamCtx.assistantMsgId());
        WorkflowCheckpoint checkpoint = new WorkflowCheckpoint(resumeNodeId, ctxJson);
        Mono.fromRunnable(() -> executionPlanStore.markPaused(planId, checkpoint))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        NodeSpec spec = def.node(resumeNodeId);
        String displayName = spec != null ? spec.displayName() : null;
        boolean tracksNodeStep = spec != null && WorkflowNodeLabels.tracksNodeStep(spec.type());
        List<StreamToken> pauseTokens = tracksNodeStep
                ? WorkflowNodeTimeline.pause(session, resumeNodeId, displayName)
                : List.of();
        return Flux.fromIterable(pauseTokens);
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
}
