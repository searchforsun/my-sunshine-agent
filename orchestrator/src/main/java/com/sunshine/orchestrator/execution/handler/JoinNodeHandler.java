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

/** 并行汇合节点 — 无 LLM，仅标记分支已汇合 */
@Component
public class JoinNodeHandler implements NodeHandler {

    @Override
    public String type() {
        return WorkflowNodeType.JOIN.id();
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        return Mono.just(NodeResult.ok(Map.of("status", "joined")));
    }
}
