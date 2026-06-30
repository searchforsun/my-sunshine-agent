package com.sunshine.orchestrator.execution.workflow;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentGroundingProperties;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.execution.WorkflowNodeLabels;
import com.sunshine.orchestrator.execution.WorkflowNodeTimeline;
import com.sunshine.orchestrator.execution.retry.NodeRetryExecutor;
import com.sunshine.orchestrator.execution.retry.NodeRetryPolicy;
import com.sunshine.orchestrator.execution.retry.OnFailureAction;
import com.sunshine.orchestrator.execution.retry.WorkflowRunSession;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.grounding.AnswerGroundingChecker;
import com.sunshine.orchestrator.grounding.GroundingEvidenceSupport;
import com.sunshine.orchestrator.grounding.GroundingVerdict;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PlanExecutionAuditService;
import com.sunshine.orchestrator.plan.PlanNodeAttempt;
import com.sunshine.orchestrator.plan.PlanNodeTrace;
import com.sunshine.orchestrator.processing.NodeAttemptMeta;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.execution.WorkflowPauseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Workflow 节点终态：Grounding、trace 落库、Timeline complete/fail */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowNodeFinalizer {

    private final ExecutionPlanStore executionPlanStore;
    private final PlanExecutionAuditService planExecutionAuditService;
    private final AnswerGroundingChecker groundingChecker;
    private final AgentGroundingProperties groundingProperties;
    private final WorkflowPauseService workflowPauseService;
    private final GenerationRegistry generationRegistry;

    public Flux<StreamToken> finalizeNode(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            NodeResult result,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            boolean tracksNodeStep,
            long startedAt,
            NodeRetryPolicy retryPolicy,
            List<NodeRetryExecutor.PlanNodeAttemptRecord> attemptRecords,
            WorkflowRunSession runSession) {
        long endedAt = System.currentTimeMillis();
        int attemptCount = attemptRecords != null ? attemptRecords.size() : 1;
        List<PlanNodeAttempt> attempts = toPlanAttempts(attemptRecords);
        if (!result.success()) {
            String err = result.safeOutputs().getOrDefault("error", "节点执行失败");
            String summary = formatFailureSummary(err, attemptCount);
            runSession.noteNodeFailure(nodeId);
            wfCtx.putNodeFailure(nodeId, err, attemptCount);
            recordNodeTrace(streamCtx, nodeId, rawSpec.type(), "failed",
                    summary, null, startedAt, endedAt, attemptCount, retryPolicy.onFailure(), attempts);
            applyOnFailure(retryPolicy.onFailure(), nodeId, err, wfCtx, runSession);
            if (tracksNodeStep) {
                return Flux.fromIterable(WorkflowNodeTimeline.fail(
                        session, nodeId, rawSpec.type(), summary, startedAt, endedAt));
            }
            return Flux.empty();
        }
        Map<String, String> outs = result.safeOutputs();
        if ("answer".equals(rawSpec.type())) {
            GroundingVerdict grounding = validateAnswerGrounding(outs, wfCtx);
            if (grounding != null && !grounding.passed()) {
                String err = grounding.reason();
                String summary = formatFailureSummary(err, attemptCount);
                runSession.noteNodeFailure(nodeId);
                wfCtx.putNodeFailure(nodeId, err, attemptCount);
                recordNodeTrace(streamCtx, nodeId, rawSpec.type(), "failed",
                        summary, null, startedAt, endedAt, attemptCount, retryPolicy.onFailure(), attempts);
                applyOnFailure(retryPolicy.onFailure(), nodeId, err, wfCtx, runSession);
                if (tracksNodeStep) {
                    return Flux.fromIterable(WorkflowNodeTimeline.fail(
                            session, nodeId, rawSpec.type(), summary, startedAt, endedAt));
                }
                return Flux.empty();
            }
        }
        wfCtx.putNode(nodeId, outs);
        runSession.noteNodeSuccess(nodeId, outs);
        if (StringUtils.hasText(streamCtx.persistedPlanId())) {
            workflowPauseService.commitContext(streamCtx.assistantMsgId(), wfCtx);
            executionPlanStore.refreshCheckpointWfCtx(streamCtx.persistedPlanId(), wfCtx);
        }
        boolean userSkipped = "true".equalsIgnoreCase(outs.get("skipped"));
        String summaryLine = resolveNodeDetail(rawSpec, outs);
        if (userSkipped) {
            String err = outs.getOrDefault("detail", outs.getOrDefault("output", summaryLine));
            summaryLine = StringUtils.hasText(err) ? "已跳过：" + err.strip() : "已跳过";
        } else if (attemptCount > 1) {
            summaryLine = summaryLine + "（第 " + attemptCount + " 次尝试成功）";
        }
        String expandDetail = resolveExpandDetail(
                rawSpec, outs, summaryLine, streamCtx.assistantMsgId());
        recordNodeTrace(streamCtx, nodeId, rawSpec.type(), "completed",
                summaryLine, expandDetail, startedAt, endedAt, attemptCount, retryPolicy.onFailure(), attempts);
        if (tracksNodeStep && isStreamingOutputNode(rawSpec.type())) {
            // answer 已在流式阶段 step_delta(result)+content 下发；llm 仍 finalize 补全
            if ("llm".equals(rawSpec.type())) {
                String answer = outs.getOrDefault("answer", outs.get("output"));
                if (StringUtils.hasText(answer)) {
                    session.appendDelta(WorkflowNodeTimeline.stepId(nodeId), "result", answer.strip());
                }
            }
        }
        List<StreamToken> all = new ArrayList<>(result.timelineTokens());
        all.addAll(result.contentTokens());
        if (tracksNodeStep) {
            all.addAll(0, WorkflowNodeTimeline.complete(
                    session, nodeId, rawSpec.type(),
                    summaryLine, expandDetail, startedAt, endedAt));
        }
        return Flux.fromIterable(all);
    }

    public static NodeResult buildSkippedNodeResult(NodeSpec spec, String err) {
        String message = StringUtils.hasText(err) ? err.strip() : "节点执行失败";
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("output", message);
        outputs.put("detail", message);
        outputs.put("skipped", "true");
        if ("tool".equals(spec.type())) {
            String tool = spec.params().getOrDefault("tool", "");
            if (StringUtils.hasText(tool)) {
                outputs.put("tool", tool.strip());
            }
        }
        return NodeResult.ok(outputs);
    }

    public Consumer<List<NodeRetryExecutor.PlanNodeAttemptRecord>> nodeAttemptsListener(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            ExecutionStreamContext streamCtx,
            boolean tracksNodeStep,
            long startedAt,
            NodeRetryPolicy retryPolicy) {
        return records -> publishNodeAttemptsProgress(
                session, nodeId, rawSpec, streamCtx, tracksNodeStep, startedAt, retryPolicy, records);
    }

    public void publishNodeAttemptsProgress(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            ExecutionStreamContext streamCtx,
            boolean tracksNodeStep,
            long startedAt,
            NodeRetryPolicy retryPolicy,
            List<NodeRetryExecutor.PlanNodeAttemptRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        if (tracksNodeStep) {
            String stepId = WorkflowNodeTimeline.stepId(nodeId);
            List<StreamToken> tokens = ProcessingTimelineSupport.run(session, () ->
                    session.updateNodeAttemptsOnStep(stepId, NodeAttemptMeta.fromRecords(records)));
            emitStreamTokens(streamCtx.assistantMsgId(), tokens);
        }
        String planId = streamCtx.persistedPlanId();
        if (!StringUtils.hasText(planId)) {
            return;
        }
        try {
            List<PlanNodeAttempt> attempts = toPlanAttempts(records);
            executionPlanStore.upsertNodeTrace(planId, new PlanNodeTrace(
                    nodeId, rawSpec.type(), "running", null, null, startedAt,
                    System.currentTimeMillis(), attempts.size(), retryPolicy.onFailure().name(), attempts));
        } catch (Exception e) {
            log.warn("[WorkflowNodeFinalizer] 写入 attempt 进度失败 planId={} node={}: {}",
                    planId, nodeId, e.getMessage());
        }
    }

    private GroundingVerdict validateAnswerGrounding(Map<String, String> outs, WorkflowContext wfCtx) {
        if (!groundingProperties.isEnabled()) {
            return null;
        }
        String answer = outs.getOrDefault("answer", outs.get("output"));
        GroundingVerdict verdict = groundingChecker.check(
                answer, GroundingEvidenceSupport.fromWorkflow(wfCtx));
        if (verdict.passed()) {
            return null;
        }
        log.warn("[WorkflowNodeFinalizer] answer Grounding 未通过: {}", verdict.reason());
        if (!groundingProperties.isBlockOnFailure()) {
            return null;
        }
        return verdict;
    }

    private static void applyOnFailure(
            OnFailureAction action,
            String nodeId,
            String err,
            WorkflowContext wfCtx,
            WorkflowRunSession runSession) {
        switch (action) {
            case SKIP -> {
                Map<String, String> placeholder = new LinkedHashMap<>();
                placeholder.put("output", "");
                placeholder.put("degraded", "true");
                wfCtx.putNode(nodeId, placeholder);
            }
            case FAIL_FAST -> runSession.abort(OnFailureAction.FAIL_FAST, "节点 " + nodeId + " 失败: " + err);
            case FALLBACK_REACT -> runSession.abort(OnFailureAction.FALLBACK_REACT, "节点 " + nodeId + " 失败: " + err);
            default -> { }
        }
    }

    private static String formatFailureSummary(String err, int attemptCount) {
        String base = "失败: " + err;
        if (attemptCount > 1) {
            return base + "（已重试 " + attemptCount + " 次）";
        }
        return base;
    }

    private static List<PlanNodeAttempt> toPlanAttempts(List<NodeRetryExecutor.PlanNodeAttemptRecord> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(r -> new PlanNodeAttempt(
                        r.attemptNo(), r.status(), r.errorClass(), r.summary(),
                        r.startedAt(), r.endedAt()))
                .collect(Collectors.toList());
    }

    private static String resolveNodeDetail(NodeSpec spec, Map<String, String> outputs) {
        if ("llm".equals(spec.type()) || "answer".equals(spec.type())) {
            return nodeDisplayName(spec) + "完成";
        }
        String detail = outputs.get("detail");
        if (detail != null && !detail.isBlank()) {
            return detail;
        }
        String hitCount = outputs.get("hitCount");
        if (hitCount != null && !hitCount.isBlank()) {
            return "命中 " + hitCount + " 条";
        }
        if ("agent".equals(spec.type())) {
            String summary = outputs.get("detail");
            if (summary != null && !summary.isBlank()) {
                return summary;
            }
        }
        return nodeDisplayName(spec) + "完成";
    }

    private static String nodeDisplayName(NodeSpec spec) {
        if (StringUtils.hasText(spec.displayName())) {
            return spec.displayName().strip();
        }
        return WorkflowNodeLabels.displayName(spec.id(), spec.type());
    }

    private static String resolveExpandDetail(
            NodeSpec spec, Map<String, String> outputs, String summaryLine, String traceMessageId) {
        if ("rag".equals(spec.type())) {
            String rewriteDetail = com.sunshine.orchestrator.rewrite.QueryRewriteTrace.combinedRagTimelineDetail(traceMessageId);
            if (rewriteDetail != null && !rewriteDetail.isBlank()) {
                return rewriteDetail;
            }
        }
        if ("agent".equals(spec.type())) {
            String expand = outputs.get("expandDetail");
            if (expand != null && !expand.isBlank()) {
                return expand.strip();
            }
            String answer = outputs.get("answer");
            if (answer != null && !answer.isBlank()) {
                return answer.strip();
            }
            return summaryLine;
        }
        if ("llm".equals(spec.type())) {
            String reasoning = outputs.get("reasoning");
            if (reasoning != null && !reasoning.isBlank()) {
                return reasoning.strip();
            }
            return null;
        }
        if ("answer".equals(spec.type())) {
            String detail = outputs.get("detail");
            if (detail != null && !detail.isBlank()) {
                String answer = outputs.getOrDefault("answer", outputs.get("output"));
                if (answer != null && !answer.equals(detail)) {
                    return detail.strip();
                }
            }
            return null;
        }
        return summaryLine;
    }

    private static boolean isStreamingOutputNode(String type) {
        return "answer".equals(type) || "llm".equals(type);
    }

    private void emitStreamTokens(String messageId, List<StreamToken> tokens) {
        if (tokens == null || tokens.isEmpty() || !StringUtils.hasText(messageId)) {
            return;
        }
        generationRegistry.findByMessageId(messageId).ifPresent(job ->
                tokens.forEach(job::emitStreamToken));
    }

    private void recordNodeTrace(
            ExecutionStreamContext streamCtx,
            String nodeId,
            String type,
            String status,
            String summary,
            String detail,
            long startedAt,
            long endedAt,
            int attemptCount,
            OnFailureAction onFailure,
            List<PlanNodeAttempt> attempts) {
        String planId = streamCtx.persistedPlanId();
        if (planId == null || planId.isBlank()) {
            return;
        }
        try {
            executionPlanStore.upsertNodeTrace(planId, new PlanNodeTrace(
                    nodeId, type, status, summary, detail, startedAt, endedAt,
                    attemptCount, onFailure.name(), attempts));
            if (attempts != null) {
                for (PlanNodeAttempt attempt : attempts) {
                    planExecutionAuditService.nodeAttempt(
                            streamCtx.conversationId(), streamCtx.assistantMsgId(),
                            streamCtx.userId(), streamCtx.tenantId(),
                            planId, nodeId, attempt, attemptCount);
                }
            }
        } catch (Exception e) {
            log.warn("[WorkflowNodeFinalizer] 写入 execution_trace 失败 planId={} node={}: {}",
                    planId, nodeId, e.getMessage());
        }
    }
}
