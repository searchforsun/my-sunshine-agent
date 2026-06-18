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
    private final LegacyWorkflowExecutor legacyWorkflowExecutor;

    public Flux<StreamToken> execute(ExecutionStreamContext ctx) {
        ExecutionPlan plan = ctx.plan();
        if (plan == null || plan.mode() != ExecutionMode.WORKFLOW) {
            return legacyWorkflowExecutor.execute(ctx);
        }
        String workflowId = plan.workflowId();
        Optional<WorkflowDefinition> defOpt = loader.load(workflowId);
        if (defOpt.isEmpty()) {
            log.warn("[WorkflowExecutor] 未找到 workflow 定义 {}，回退 legacy", workflowId);
            return legacyWorkflowExecutor.execute(ctx);
        }
        return executeDefinition(defOpt.get(), ctx);
    }

    private Flux<StreamToken> executeDefinition(WorkflowDefinition def, ExecutionStreamContext streamCtx) {
        WorkflowContext wfCtx = initContext(streamCtx);
        ProcessingTimelineSession session = ProcessingTimelineSupport.newSession();
        session.bindUserQuery(streamCtx.userContent());
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
                            String detail = resolveNodeDetail(rawSpec, result.safeOutputs());
                            List<StreamToken> all = new ArrayList<>(result.contentTokens());
                            if (showTimeline) {
                                all.addAll(0, WorkflowNodeTimeline.complete(
                                        session, nodeId, rawSpec.type(), detail, startedAt, endedAt));
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
        return WorkflowNodeLabels.displayName(spec.id(), spec.type()) + "完成";
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
