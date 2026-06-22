package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
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
                ? WorkflowNodeTimeline.start(session, nodeId, rawSpec.type())
                : List.of();

        return Flux.concat(
                Flux.fromIterable(startTokens),
                handler.run(resolved, wfCtx, streamCtx)
                        .flatMapMany(result -> {
                            long endedAt = System.currentTimeMillis();
                            if (!result.success()) {
                                String err = result.safeOutputs().getOrDefault("error", "节点执行失败");
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
                            List<StreamToken> all = new ArrayList<>(result.contentTokens());
                            if (showTimeline) {
                                all.addAll(0, WorkflowNodeTimeline.complete(
                                        session, nodeId, rawSpec.type(),
                                        summaryLine, expandDetail, startedAt, endedAt));
                            }
                            return Flux.fromIterable(all);
                        })
        );
    }

    private static String resolveNodeDetail(NodeSpec spec, Map<String, String> outputs) {
        String detail = outputs.get("detail");
        if (detail != null && !detail.isBlank()) {
            return detail;
        }
        String hitCount = outputs.get("hitCount");
        if (hitCount != null && !hitCount.isBlank()) {
            return "命中 " + hitCount + " 条";
        }
        if ("llm".equals(spec.type())) {
            String answer = outputs.getOrDefault("answer", outputs.get("output"));
            if (answer != null && !answer.isBlank()) {
                return "已完成回复";
            }
        }
        if ("agent".equals(spec.type())) {
            String summary = outputs.get("detail");
            if (summary != null && !summary.isBlank()) {
                return summary;
            }
        }
        return WorkflowNodeLabels.displayName(spec.id(), spec.type()) + "完成";
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
            String answer = outputs.get("answer");
            if (answer != null && !answer.isBlank()) {
                return answer.strip();
            }
            return summaryLine;
        }
        return summaryLine;
    }

    private static NodeSpec resolveParams(NodeSpec spec, WorkflowContext ctx) {
        Map<String, String> resolved = new LinkedHashMap<>();
        if (spec.params() != null) {
            spec.params().forEach((k, v) ->
                    resolved.put(k, TemplateResolver.resolve(v, ctx)));
        }
        return new NodeSpec(spec.id(), spec.type(), resolved);
    }
}
