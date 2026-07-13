package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.execution.WorkflowNodeType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/** BPMN 并行分叉网关 — 路由节点，无业务副作用 */
@Component
public class ParallelGatewayNodeHandler implements NodeHandler {

    @Override
    public String type() {
        return WorkflowNodeType.PARALLEL_GATEWAY.id();
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        return Mono.just(NodeResult.ok(Map.of("status", "forked")));
    }
}
