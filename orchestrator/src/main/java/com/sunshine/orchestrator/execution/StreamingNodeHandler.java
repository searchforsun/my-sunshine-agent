package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import reactor.core.publisher.Flux;

/** 支持流式执行的 Workflow 节点（如 llm） */
public interface StreamingNodeHandler extends NodeHandler {

    Flux<StreamToken> streamTokens(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx, String nodeId);

    NodeResult buildResult(WorkflowStreamCollector collector);
}
