package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.plan.ExecutionPlanStore;
import com.sunshine.orchestrator.plan.PlanNodeTrace;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Workflow DAG 线性执行引擎
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowExecutor {

    private final WorkflowDefinitionLoader loader;
    private final NodeHandlerRegistry registry;
    private final ExecutionPlanStore executionPlanStore;
    private final WorkflowNodeLabelService labelService;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        ExecutionPlan plan = ctx.plan();
        if (plan == null || plan.mode() != ExecutionMode.WORKFLOW) {
            return Flux.just(StreamToken.content("内部错误：WorkflowExecutor 收到非 workflow 计划"));
        }
        String workflowId = plan.workflowId();
        Optional<WorkflowDefinition> defOpt = loader.load(workflowId);
        if (defOpt.isEmpty()) {
            log.error("[WorkflowExecutor] 未找到 workflow 定义: {}", workflowId);
            return Flux.just(StreamToken.content(
                    "工作流「" + workflowId + "」未定义，请联系管理员。"));
        }
        return executeDefinition(defOpt.get(), ctx);
    }

    /** 动态 Plan 物化后的 DAG 执行（plan 步由 PlanWorkflowExecutor 前置下发） */
    public Flux<StreamToken> executeDynamicDefinition(WorkflowDefinition def, ExecutionStreamContext streamCtx) {
        labelService.bindRuntimeNodeLabels(def);
        WorkflowContext wfCtx = initContext(streamCtx);
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(streamCtx.userContent());
        session.bindTraceMessageId(streamCtx.assistantMsgId());
        return Flux.fromIterable(def.linearOrder())
                .concatMap(nodeId -> executeNode(session, def, nodeId, wfCtx, streamCtx))
                .doFinally(signal -> labelService.clearRuntimeNodeLabels());
    }

    private Flux<StreamToken> executeDefinition(WorkflowDefinition def, ExecutionStreamContext streamCtx) {
        WorkflowContext wfCtx = initContext(streamCtx);
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(streamCtx.userContent());
        session.bindTraceMessageId(streamCtx.assistantMsgId());
        List<StreamToken> planTokens = WorkflowNodeTimeline.planStep(session, def);

        return Flux.concat(
                Flux.fromIterable(planTokens),
                Flux.fromIterable(def.linearOrder())
                        .concatMap(nodeId -> executeNode(session, def, nodeId, wfCtx, streamCtx))
        );
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

    private Flux<StreamToken> executeNode(
            ProcessingTimelineSession session,
            WorkflowDefinition def,
            String nodeId,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx) {

        NodeSpec rawSpec = def.node(nodeId);
        if (rawSpec == null) {
            log.warn("[WorkflowExecutor] 节点 {} 不存在", nodeId);
            return Flux.empty();
        }

        NodeSpec resolved = resolveParams(rawSpec, wfCtx);
        NodeHandler handler = registry.require(rawSpec.type());
        boolean showTimeline = WorkflowNodeLabels.isVisibleNode(rawSpec.type());
        long startedAt = System.currentTimeMillis();

        List<StreamToken> startTokens = showTimeline
                ? WorkflowNodeTimeline.start(session, nodeId, rawSpec.type(), rawSpec.displayName())
                : List.of();

        return Flux.concat(
                Flux.fromIterable(startTokens),
                runNode(session, nodeId, rawSpec, resolved, handler, wfCtx, streamCtx, showTimeline, startedAt)
        );
    }

    private Flux<StreamToken> runNode(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            NodeSpec resolved,
            NodeHandler handler,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            boolean showTimeline,
            long startedAt) {
        if (handler instanceof StreamingNodeHandler streaming) {
            return executeStreamingNode(
                    session, nodeId, rawSpec, resolved, streaming, wfCtx, streamCtx, showTimeline, startedAt);
        }
        return handler.run(resolved, wfCtx, streamCtx)
                .flatMapMany(result -> finalizeNode(
                        session, nodeId, rawSpec, result, wfCtx, streamCtx, showTimeline, startedAt));
    }

    private Flux<StreamToken> executeStreamingNode(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            NodeSpec resolved,
            StreamingNodeHandler handler,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            boolean showTimeline,
            long startedAt) {
        WorkflowStreamCollector collector = new WorkflowStreamCollector();
        return handler.streamTokens(resolved, wfCtx, streamCtx, nodeId)
                .doOnNext(collector::accept)
                .concatWith(Flux.defer(() -> finalizeNode(
                        session, nodeId, rawSpec, handler.buildResult(collector),
                        wfCtx, streamCtx, showTimeline, startedAt)))
                .onErrorResume(e -> finalizeNode(
                        session, nodeId, rawSpec, NodeResult.fail(e.getMessage()),
                        wfCtx, streamCtx, showTimeline, startedAt));
    }

    private Flux<StreamToken> finalizeNode(
            ProcessingTimelineSession session,
            String nodeId,
            NodeSpec rawSpec,
            NodeResult result,
            WorkflowContext wfCtx,
            ExecutionStreamContext streamCtx,
            boolean showTimeline,
            long startedAt) {
        long endedAt = System.currentTimeMillis();
        if (!result.success()) {
            String err = result.safeOutputs().getOrDefault("error", "节点执行失败");
            recordNodeTrace(streamCtx, nodeId, rawSpec.type(), "failed",
                    "失败: " + err, null, startedAt, endedAt);
            if (showTimeline) {
                List<StreamToken> failComplete = WorkflowNodeTimeline.complete(
                        session, nodeId, rawSpec.type(),
                        "失败: " + err, startedAt, endedAt);
                return Flux.fromIterable(failComplete);
            }
            return Flux.empty();
        }
        wfCtx.putNode(nodeId, result.safeOutputs());
        Map<String, String> outs = result.safeOutputs();
        String summaryLine = resolveNodeDetail(rawSpec, outs);
        String expandDetail = resolveExpandDetail(
                rawSpec, outs, summaryLine, streamCtx.assistantMsgId());
        recordNodeTrace(streamCtx, nodeId, rawSpec.type(), "completed",
                summaryLine, expandDetail, startedAt, endedAt);
        if (showTimeline && isStreamingOutputNode(rawSpec.type())) {
            String answer = outs.getOrDefault("answer", outs.get("output"));
            if (StringUtils.hasText(answer)) {
                session.appendDelta(WorkflowNodeTimeline.stepId(nodeId), "result", answer.strip());
            }
        }
        List<StreamToken> all = new ArrayList<>(result.contentTokens());
        if (showTimeline) {
            all.addAll(0, WorkflowNodeTimeline.complete(
                    session, nodeId, rawSpec.type(),
                    summaryLine, expandDetail, startedAt, endedAt));
        }
        return Flux.fromIterable(all);
    }

    private static String resolveNodeDetail(NodeSpec spec, Map<String, String> outputs) {
        // answer/llm 主行 after 仅用节点 displayName，模型正文走 reasoning/result
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

    /** 优先 NodeSpec.displayName，避免 Reactor 切线程后 ThreadLocal 标签丢失 */
    private static String nodeDisplayName(NodeSpec spec) {
        if (StringUtils.hasText(spec.displayName())) {
            return spec.displayName().strip();
        }
        return WorkflowNodeLabels.displayName(spec.id(), spec.type());
    }

    /** agent 节点：展开区下发完整 answer，主行 after 由 AgentNodeDetailSummarizer 提供 */
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
            String reasoning = outputs.get("reasoning");
            if (reasoning != null && !reasoning.isBlank()) {
                return reasoning.strip();
            }
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

    private static NodeSpec resolveParams(NodeSpec spec, WorkflowContext ctx) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (spec.params() != null) {
            spec.params().forEach((k, v) ->
                    resolved.put(k, TemplateResolver.resolve(v, ctx)));
        }
        return new NodeSpec(spec.id(), spec.type(), resolved, spec.displayName());
    }

    private void recordNodeTrace(
            ExecutionStreamContext streamCtx,
            String nodeId,
            String type,
            String status,
            String summary,
            String detail,
            long startedAt,
            long endedAt) {
        String planId = streamCtx.persistedPlanId();
        if (planId == null || planId.isBlank()) {
            return;
        }
        try {
            executionPlanStore.appendNodeTrace(planId, new PlanNodeTrace(
                    nodeId, type, status, summary, detail, startedAt, endedAt));
        } catch (Exception e) {
            log.warn("[WorkflowExecutor] 写入 execution_trace 失败 planId={} node={}: {}",
                    planId, nodeId, e.getMessage());
        }
    }
}
