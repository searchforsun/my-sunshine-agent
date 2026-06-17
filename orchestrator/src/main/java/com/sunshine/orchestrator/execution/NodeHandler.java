package com.sunshine.orchestrator.execution;

import reactor.core.publisher.Mono;

/**
 * Workflow DAG 节点执行器
 */
public interface NodeHandler {

    String type();

    Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx);
}
