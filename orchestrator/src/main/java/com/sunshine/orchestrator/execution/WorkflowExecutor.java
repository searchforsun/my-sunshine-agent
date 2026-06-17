package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.routing.ExecutionMode;
import com.sunshine.orchestrator.routing.ExecutionPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
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
        List<StreamToken> planTokens = WorkflowNodeTimeline.planStep(def.linearOrder());

        return Flux.concat(
                Flux.fromIterable(planTokens),
                Flux.fromIterable(def.linearOrder())
                        .concatMap(nodeId -> executeNode(def, nodeId, wfCtx, streamCtx))
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

        List<StreamToken> startTokens = WorkflowNodeTimeline.start(nodeId, rawSpec.type());

        return Flux.concat(
                Flux.fromIterable(startTokens),
                handler.run(resolved, wfCtx, streamCtx)
                        .flatMapMany(result -> {
                            if (!result.success()) {
                                String err = result.safeOutputs().getOrDefault("error", "节点执行失败");
                                List<StreamToken> failComplete = WorkflowNodeTimeline.complete(
                                        nodeId, rawSpec.type(), "失败: " + err);
                                return Flux.fromIterable(failComplete);
                            }
                            wfCtx.putNode(nodeId, result.safeOutputs());
                            String detail = result.safeOutputs().getOrDefault(
                                    "detail", result.safeOutputs().getOrDefault("hitCount", null));
                            List<StreamToken> completeTokens = WorkflowNodeTimeline.complete(
                                    nodeId, rawSpec.type(), detail);
                            List<StreamToken> all = new ArrayList<>();
                            all.addAll(result.timelineTokens());
                            all.addAll(completeTokens);
                            all.addAll(result.contentTokens());
                            return Flux.fromIterable(all);
                        })
        );
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
