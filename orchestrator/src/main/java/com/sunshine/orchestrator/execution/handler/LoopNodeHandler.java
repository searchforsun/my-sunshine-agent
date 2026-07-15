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

/** loop 容器 — 迭代由 {@link com.sunshine.orchestrator.execution.WorkflowExecutor} 驱动 */
@Component
public class LoopNodeHandler implements NodeHandler {

    @Override
    public String type() {
        return WorkflowNodeType.LOOP.id();
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        return Mono.just(NodeResult.ok(Map.of("status", "looping")));
    }
}
