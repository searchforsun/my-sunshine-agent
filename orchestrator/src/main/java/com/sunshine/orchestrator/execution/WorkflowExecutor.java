package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.retry.WorkflowRunSession;
import com.sunshine.orchestrator.execution.workflow.WorkflowNodeRunner;
import com.sunshine.orchestrator.execution.workflow.WorkflowStaticPlanRunner;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PausePhase;
import com.sunshine.orchestrator.plan.PlanExecutionSchedule;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Workflow DAG 执行引擎（串行 + 并行 fan-out/join） */
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
        return executeSchedule(def, session, def.executionSteps(), wfCtx, streamCtx, runSession, planWorkflow)
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
        List<PlanExecutionSchedule.Step> steps = resolveResumeSteps(def, checkpoint.resumeNodeId(), wfCtx);
        return executeSchedule(def, session, steps, wfCtx, streamCtx, runSession, planWorkflow)
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signal -> {
                    labelService.clearRuntimeNodeLabels();
                    if (planWorkflow) {
                        workflowPauseService.clearRun(streamCtx.assistantMsgId());
                    }
                });
    }

    private Flux<StreamToken> executeSchedule(
            WorkflowDefinition def,
            ProcessingTimelineSession session,
            List<PlanExecutionSchedule.Step> steps,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            WorkflowRunSession runSession,
            boolean planWorkflow) {
        if (steps == null || steps.isEmpty()) {
            return executeNodeOrder(def.linearOrder(), session, def, wfCtx, streamCtx, runSession, planWorkflow);
        }
        return Flux.fromIterable(steps)
                .concatMap(step -> executeStep(step, session, def, wfCtx, streamCtx, runSession, planWorkflow));
    }

    private Flux<StreamToken> executeStep(
            PlanExecutionSchedule.Step step,
            ProcessingTimelineSession session,
            WorkflowDefinition def,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            WorkflowRunSession runSession,
            boolean planWorkflow) {
        if (step instanceof PlanExecutionSchedule.Single single) {
            return executeOneNode(single.nodeId(), session, def, wfCtx, streamCtx, runSession, planWorkflow);
        }
        if (step instanceof PlanExecutionSchedule.Parallel parallel) {
            List<String> pending = parallel.branchNodeIds().stream()
                    .filter(id -> !isNodeCompleted(wfCtx, id))
                    .toList();
            Flux<StreamToken> branches = pending.isEmpty()
                    ? Flux.empty()
                    : Flux.merge(pending.stream()
                            .map(id -> executeOneNode(id, session, def, wfCtx, streamCtx, runSession, planWorkflow))
                            .toList());
            if (isNodeCompleted(wfCtx, parallel.joinNodeId())) {
                return branches;
            }
            return branches.concatWith(executeOneNode(
                    parallel.joinNodeId(), session, def, wfCtx, streamCtx, runSession, planWorkflow));
        }
        if (step instanceof PlanExecutionSchedule.Exclusive exclusive) {
            return executeExclusive(exclusive, session, def, wfCtx, streamCtx, runSession, planWorkflow);
        }
        return Flux.empty();
    }

    private Flux<StreamToken> executeExclusive(
            PlanExecutionSchedule.Exclusive exclusive,
            ProcessingTimelineSession session,
            WorkflowDefinition def,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            WorkflowRunSession runSession,
            boolean planWorkflow) {
        Flux<StreamToken> gateway = isNodeCompleted(wfCtx, exclusive.gatewayNodeId())
                ? Flux.empty()
                : executeOneNode(exclusive.gatewayNodeId(), session, def, wfCtx, streamCtx, runSession, planWorkflow);
        return gateway.concatWith(Flux.defer(() -> {
            PlanExecutionSchedule.ExclusiveArm picked = pickExclusiveArm(exclusive.arms(), wfCtx);
            if (picked == null) {
                return Flux.error(new IllegalStateException(
                        "条件分支 " + exclusive.gatewayNodeId() + " 无命中条件且无 default 出边"));
            }
            List<String> pending = picked.pathNodeIds().stream()
                    .filter(id -> !isNodeCompleted(wfCtx, id))
                    .toList();
            return executeNodeOrder(pending, session, def, wfCtx, streamCtx, runSession, planWorkflow);
        }));
    }

    private static PlanExecutionSchedule.ExclusiveArm pickExclusiveArm(
            List<PlanExecutionSchedule.ExclusiveArm> arms,
            WorkflowContext wfCtx) {
        PlanExecutionSchedule.ExclusiveArm fallback = null;
        for (PlanExecutionSchedule.ExclusiveArm arm : arms) {
            if (arm.isDefault()) {
                fallback = arm;
                continue;
            }
            if (EdgeConditionEvaluator.matches(arm.condition(), wfCtx)) {
                return arm;
            }
        }
        return fallback;
    }

    private Flux<StreamToken> executeOneNode(
            String nodeId,
            ProcessingTimelineSession session,
            WorkflowDefinition def,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            WorkflowRunSession runSession,
            boolean planWorkflow) {
        if (planWorkflow && workflowPauseService.consumePauseRequested(streamCtx.assistantMsgId())) {
            return pauseBeforeNode(session, def, nodeId, wfCtx, streamCtx);
        }
        workflowPauseService.setCurrentNode(streamCtx.assistantMsgId(), nodeId);
        return nodeRunner.executeNode(session, def, nodeId, wfCtx, streamCtx, runSession, planWorkflow);
    }

    private List<PlanExecutionSchedule.Step> resolveResumeSteps(
            WorkflowDefinition def,
            String resumeNodeId,
            WorkflowContext wfCtx) {
        List<PlanExecutionSchedule.Step> steps = def.executionSteps();
        if (steps == null || steps.isEmpty() || !StringUtils.hasText(resumeNodeId)) {
            List<String> order = def.linearOrder();
            int startIdx = order.indexOf(resumeNodeId);
            if (startIdx < 0) {
                startIdx = 0;
            }
            return order.subList(startIdx, order.size()).stream()
                    .map(PlanExecutionSchedule.Single::new)
                    .map(PlanExecutionSchedule.Step.class::cast)
                    .toList();
        }
        List<PlanExecutionSchedule.Step> remaining = new ArrayList<>();
        boolean found = false;
        for (PlanExecutionSchedule.Step step : steps) {
            if (!found) {
                if (stepContains(step, resumeNodeId)) {
                    found = true;
                    remaining.add(step);
                }
                continue;
            }
            remaining.add(step);
        }
        if (!found) {
            return steps;
        }
        if (remaining.size() == 1 && remaining.get(0) instanceof PlanExecutionSchedule.Parallel parallel
                && parallel.branchNodeIds().contains(resumeNodeId)
                && !parallel.joinNodeId().equals(resumeNodeId)) {
            return remaining;
        }
        return remaining;
    }

    private static boolean stepContains(PlanExecutionSchedule.Step step, String nodeId) {
        if (step instanceof PlanExecutionSchedule.Single single) {
            return single.nodeId().equals(nodeId);
        }
        if (step instanceof PlanExecutionSchedule.Parallel parallel) {
            return parallel.branchNodeIds().contains(nodeId) || parallel.joinNodeId().equals(nodeId);
        }
        if (step instanceof PlanExecutionSchedule.Exclusive exclusive) {
            if (exclusive.gatewayNodeId().equals(nodeId)) {
                return true;
            }
            return exclusive.arms().stream().anyMatch(arm ->
                    arm.targetNodeId().equals(nodeId) || arm.pathNodeIds().contains(nodeId));
        }
        return false;
    }

    private static boolean isNodeCompleted(WorkflowContext wfCtx, String nodeId) {
        Map<String, String> node = wfCtx.node(nodeId);
        if (node == null || node.isEmpty()) {
            return false;
        }
        if (StringUtils.hasText(node.get("output")) || StringUtils.hasText(node.get("answer"))) {
            return true;
        }
        String status = node.get("status");
        return "joined".equals(status) || "routed".equals(status) || "forked".equals(status);
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
                .concatMap(nodeId -> executeOneNode(
                        nodeId, session, def, wfCtx, streamCtx, runSession, planWorkflow));
    }

    private Flux<StreamToken> pauseBeforeNode(
            ProcessingTimelineSession session,
            WorkflowDefinition def,
            String resumeNodeId,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx) {
        String planId = streamCtx.persistedPlanId();
        String ctxJson = workflowPauseService.getCommittedContextJson(streamCtx.assistantMsgId());
        WorkflowCheckpoint checkpoint = new WorkflowCheckpoint(resumeNodeId, ctxJson, PausePhase.EXECUTING, null);
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
