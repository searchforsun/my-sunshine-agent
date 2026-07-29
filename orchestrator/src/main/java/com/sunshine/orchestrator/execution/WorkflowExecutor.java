package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.agent.StepEventBridge;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.loop.LoopBodyFlushFold;
import com.sunshine.orchestrator.execution.loop.LoopBodyTimelineBridge;
import com.sunshine.orchestrator.execution.retry.OnFailureAction;
import com.sunshine.orchestrator.execution.retry.WorkflowRunSession;
import com.sunshine.orchestrator.execution.workflow.WorkflowNodeRunner;
import com.sunshine.orchestrator.execution.workflow.WorkflowStaticPlanRunner;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PausePhase;
import com.sunshine.orchestrator.plan.PlanEdgeCondition;
import com.sunshine.orchestrator.plan.PlanEdgeConditionGroup;
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

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        if (step instanceof PlanExecutionSchedule.Loop loop) {
            return executeLoop(loop, session, def, wfCtx, streamCtx, runSession, planWorkflow);
        }
        return Flux.empty();
    }

    private Flux<StreamToken> executeLoop(
            PlanExecutionSchedule.Loop loop,
            ProcessingTimelineSession session,
            WorkflowDefinition def,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            WorkflowRunSession runSession,
            boolean planWorkflow) {
        String loopId = loop.loopNodeId();
        NodeSpec loopSpec = def.node(loopId);
        String loopLabel = loopSpec != null && StringUtils.hasText(loopSpec.displayName())
                ? loopSpec.displayName()
                : loopId;
        LoopBodyTimelineBridge bridge = new LoopBodyTimelineBridge(loopId, loopLabel, loop.bodyNodeIds());
        AtomicInteger foldIter = new AtomicInteger(1);
        if (StringUtils.hasText(streamCtx.assistantMsgId())) {
            StepEventBridge.bindLoopBodyFold(
                    streamCtx.assistantMsgId(),
                    new LoopBodyFlushFold(bridge, foldIter)::apply);
        }
        Flux<StreamToken> open = hasLoopSettled(wfCtx, loopId)
                ? Flux.empty()
                : executeOneNode(loopId, session, def, wfCtx, streamCtx, runSession, planWorkflow);
        return open.concatWith(Flux.defer(() ->
                        runLoopIterations(
                                loop, bridge, foldIter, session, def, wfCtx, streamCtx, runSession, planWorkflow)))
                .doFinally(sig -> {
                    if (StringUtils.hasText(streamCtx.assistantMsgId())) {
                        StepEventBridge.clearLoopBodyFold(streamCtx.assistantMsgId());
                    }
                });
    }

    private Flux<StreamToken> runLoopIterations(
            PlanExecutionSchedule.Loop loop,
            LoopBodyTimelineBridge bridge,
            AtomicInteger foldIter,
            ProcessingTimelineSession session,
            WorkflowDefinition def,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            WorkflowRunSession runSession,
            boolean planWorkflow) {
        if (hasLoopSettled(wfCtx, loop.loopNodeId())) {
            return Flux.empty();
        }
        NodeSpec loopSpec = def.node(loop.loopNodeId());
        Map<String, Object> params = loopSpec != null ? loopSpec.params() : Map.of();
        int maxIterations = parseMaxIterations(params);
        String onMax = readParamString(params, "onMaxIterations", "fail_fast").strip().toLowerCase();
        PlanEdgeConditionGroup conditionGroup = parseLoopConditionGroup(params);
        AtomicInteger iter = new AtomicInteger(0);
        AtomicReference<String> buffer = new AtomicReference<>("");
        return loopCycle(
                loop, bridge, foldIter, conditionGroup, maxIterations, onMax, iter, buffer,
                session, def, wfCtx, streamCtx, runSession, planWorkflow);
    }

    private static PlanEdgeConditionGroup parseLoopConditionGroup(Map<String, Object> params) {
        Object conditionsObj = params.get("conditions");
        if (conditionsObj instanceof JsonNode conditionsNode && conditionsNode.isArray()) {
            String logic = readParamString(params, "conditionLogic", "and");
            List<PlanEdgeCondition> items = new ArrayList<>();
            for (JsonNode item : conditionsNode) {
                String left = item.has("left") ? item.get("left").asText("") : "";
                String op = item.has("op") ? item.get("op").asText("") : "";
                String right = item.has("right") ? item.get("right").asText("") : "";
                if (!op.isBlank()) {
                    items.add(new PlanEdgeCondition(left, op, right));
                }
            }
            return new PlanEdgeConditionGroup(logic, items);
        }
        // 无条件 -> 空组（永远继续，靠 maxIterations 兜底）
        return PlanEdgeConditionGroup.empty();
    }

    private Flux<StreamToken> loopCycle(
            PlanExecutionSchedule.Loop loop,
            LoopBodyTimelineBridge bridge,
            AtomicInteger foldIter,
            PlanEdgeConditionGroup conditionGroup,
            int maxIterations,
            String onMax,
            AtomicInteger iter,
            AtomicReference<String> buffer,
            ProcessingTimelineSession session,
            WorkflowDefinition def,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            WorkflowRunSession runSession,
            boolean planWorkflow) {
        // do-while：至少一轮；继续条件在 body 之后求值
        if (iter.get() >= maxIterations) {
            return applyLoopMaxIterations(loop.loopNodeId(), onMax, buffer.get(), iter.get(), wfCtx, runSession)
                    .concatWith(Flux.just(loopCompleteToken(
                            loop.loopNodeId(),
                            def.node(loop.loopNodeId()),
                            buffer.get(),
                            iter.get(),
                            bridge.subSteps())));
        }
        List<String> body = loop.bodyNodeIds();
        if (body.isEmpty()) {
            return Flux.error(new IllegalStateException("loop " + loop.loopNodeId() + " body 为空"));
        }
        int round = iter.get() + 1;
        foldIter.set(round);
        return executeNodeOrder(body, session, def, wfCtx, streamCtx, runSession, planWorkflow)
                .concatMap(token -> {
                    if (bridge.isBodyToken(token)) {
                        return Flux.fromIterable(bridge.wrap(token, round));
                    }
                    return Flux.just(token);
                })
                .concatWith(Flux.defer(() -> {
                    buffer.set(resolveBodyTailOutput(wfCtx, body));
                    iter.incrementAndGet();
                    if (!EdgeConditionEvaluator.matchesGroup(conditionGroup, wfCtx)) {
                        settleLoop(wfCtx, loop.loopNodeId(), buffer.get(), iter.get(), "completed");
                        return Flux.just(loopCompleteToken(
                                loop.loopNodeId(),
                                def.node(loop.loopNodeId()),
                                buffer.get(),
                                iter.get(),
                                bridge.subSteps()));
                    }
                    return loopCycle(
                            loop, bridge, foldIter, conditionGroup, maxIterations, onMax, iter, buffer,
                            session, def, wfCtx, streamCtx, runSession, planWorkflow);
                }));
    }

    private Flux<StreamToken> applyLoopMaxIterations(
            String loopId,
            String onMax,
            String buffer,
            int iterations,
            WorkflowContext wfCtx,
            WorkflowRunSession runSession) {
        if ("exit".equals(onMax)) {
            settleLoop(wfCtx, loopId, buffer, iterations, "max_exit");
            return Flux.empty();
        }
        if ("fallback_react".equals(onMax)) {
            settleLoop(wfCtx, loopId, buffer, iterations, "max_fallback");
            runSession.abort(OnFailureAction.FALLBACK_REACT, "loop " + loopId + " 达到 maxIterations");
            return Flux.empty();
        }
        settleLoop(wfCtx, loopId, buffer, iterations, "max_fail");
        runSession.abort(OnFailureAction.FAIL_FAST, "loop " + loopId + " 达到 maxIterations");
        return Flux.empty();
    }

    private static StreamToken loopCompleteToken(
            String loopId,
            NodeSpec loopSpec,
            String output,
            int iterations,
            List<com.sunshine.orchestrator.agent.ProcessingStep> subSteps) {
        return StreamToken.step(loopCompleteStep(loopId, loopSpec, output, iterations, subSteps));
    }

    private static com.sunshine.orchestrator.agent.ProcessingStep loopCompleteStep(
            String loopId,
            NodeSpec loopSpec,
            String output,
            int iterations,
            List<com.sunshine.orchestrator.agent.ProcessingStep> subSteps) {
        String label = loopSpec != null && StringUtils.hasText(loopSpec.displayName())
                ? loopSpec.displayName()
                : loopId;
        long ts = System.currentTimeMillis();
        String after = label + "完成（" + Math.max(0, iterations) + " 轮）";
        return new com.sunshine.orchestrator.agent.ProcessingStep(
                WorkflowNodeTimeline.stepId(loopId),
                "node",
                "done",
                new com.sunshine.orchestrator.processing.StepSummary(null, label, after),
                null,
                ts,
                null,
                null,
                null,
                output,
                output,
                ts,
                label,
                null,
                null,
                subSteps);
    }

    private static void settleLoop(
            WorkflowContext wfCtx,
            String loopId,
            String output,
            int iterations,
            String status) {
        Map<String, TypedValue> out = new LinkedHashMap<>();
        out.put("output", TypedValue.scalar(output != null ? output : ""));
        out.put("status", TypedValue.scalar(status));
        if (iterations >= 0) {
            out.put("iterations", TypedValue.scalar(String.valueOf(iterations)));
        }
        wfCtx.putNode(loopId, out);
    }

    private static String resolveBodyTailOutput(WorkflowContext wfCtx, List<String> body) {
        String last = body.get(body.size() - 1);
        Map<String, TypedValue> node = wfCtx.node(last);
        String answer = renderScalar(node, "answer");
        if (StringUtils.hasText(answer)) {
            return answer;
        }
        return renderScalar(node, "output");
    }

    private static boolean hasLoopSettled(WorkflowContext wfCtx, String loopId) {
        Map<String, TypedValue> node = wfCtx.node(loopId);
        if (node == null || node.isEmpty()) {
            return false;
        }
        String status = renderScalar(node, "status");
        return "completed".equals(status) || "max_exit".equals(status)
                || "max_fail".equals(status) || "max_fallback".equals(status)
                || StringUtils.hasText(renderScalar(node, "output")) && !"looping".equals(status);
    }

    private static int parseMaxIterations(Map<String, Object> params) {
        try {
            int n = Integer.parseInt(readParamString(params, "maxIterations", "3").strip());
            return Math.max(1, Math.min(5, n));
        } catch (NumberFormatException e) {
            return 3;
        }
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
            // 空条件组（PlanEdge 构造时把空 group 折叠为 null）不可作为命中条件，
            // 否则 matchesGroup(null, ctx) 会返回 true 导致误选；跳过，回落到 default。
            if (arm.condition() == null) {
                continue;
            }
            if (EdgeConditionEvaluator.matchesGroup(arm.condition(), wfCtx)) {
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
        if (step instanceof PlanExecutionSchedule.Loop loop) {
            return loop.loopNodeId().equals(nodeId) || loop.bodyNodeIds().contains(nodeId);
        }
        return false;
    }

    private static boolean isNodeCompleted(WorkflowContext wfCtx, String nodeId) {
        Map<String, TypedValue> node = wfCtx.node(nodeId);
        if (node == null || node.isEmpty()) {
            return false;
        }
        if (StringUtils.hasText(renderScalar(node, "output")) || StringUtils.hasText(renderScalar(node, "answer"))) {
            String status = renderScalar(node, "status");
            if ("looping".equals(status)) {
                return false;
            }
            return true;
        }
        String status = renderScalar(node, "status");
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
        Map<String, TypedValue> start = new LinkedHashMap<>();
        if (StringUtils.hasText(streamCtx.userContent())) {
            start.put("userQuery", TypedValue.scalar(streamCtx.userContent()));
        }
        wfCtx.putNode("start", start);
        ExecutionPlan plan = streamCtx.plan();
        if (plan != null && plan.params() != null) {
            Map<String, TypedValue> planParams = new LinkedHashMap<>();
            plan.params().forEach((k, v) -> planParams.put(k, TypedValue.scalar(v)));
            wfCtx.putNode("plan", planParams);
        }
        return wfCtx;
    }

    private static String renderScalar(Map<String, TypedValue> outputs, String key) {
        TypedValue v = outputs.get(key);
        return v != null ? v.render() : null;
    }

    private static String readParamString(Map<String, Object> params, String key, String defaultValue) {
        if (params == null) {
            return defaultValue;
        }
        Object v = params.get(key);
        return v != null ? v.toString() : defaultValue;
    }
}
