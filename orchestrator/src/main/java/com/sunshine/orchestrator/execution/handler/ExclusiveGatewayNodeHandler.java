package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.common.workflow.WorkflowNodeType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

/** BPMN 排他网关 — 路由节点；分支选择在 {@link com.sunshine.orchestrator.plan.PlanExecutionSchedule} */
@Component
public class ExclusiveGatewayNodeHandler implements NodeHandler {

    @Override
    public String type() {
        return WorkflowNodeType.EXCLUSIVE_GATEWAY.id();
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        return Mono.just(NodeResult.ok(Map.of("status", "routed")));
    }
}
