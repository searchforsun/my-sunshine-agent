package com.sunshine.orchestrator.execution;

import com.sunshine.orchestrator.client.StreamToken;
import reactor.core.publisher.Flux;

/** 支持流式执行的 Workflow 节点（如 llm / agent） */
public interface StreamingNodeHandler extends NodeHandler {

    Flux<StreamToken> streamTokens(
            NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx, String nodeId);

    default Flux<StreamToken> streamTokens(
            NodeSpec spec,
            WorkflowContext ctx,
            ExecutionStreamContext streamCtx,
            String nodeId,
            WorkflowStreamCollector collector) {
        return streamTokens(spec, ctx, streamCtx, nodeId);
    }

    default WorkflowStreamCollector createStreamCollector(NodeSpec spec, String nodeId) {
        return new WorkflowStreamCollector();
    }

    NodeResult buildResult(WorkflowStreamCollector collector);
}
