package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.StreamingNodeHandler;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.execution.WorkflowStreamCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * @deprecated 请使用 answer 节点承载 prompt；保留以兼容未迁移的静态 workflow。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Deprecated
public class LlmNodeHandler implements StreamingNodeHandler {

    private final LlmGatewayClient llmGateway;

    @Override
    public String type() {
        return "llm";
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        WorkflowStreamCollector collector = new WorkflowStreamCollector();
        return streamTokens(spec, ctx, streamCtx, spec.id())
                .doOnNext(collector::accept)
                .then(Mono.fromSupplier(() -> buildResult(collector)))
                .onErrorResume(e -> {
                    log.warn("[LlmNodeHandler] 补全失败: {}", e.getMessage());
                    return Mono.just(NodeResult.fail(e.getMessage()));
                });
    }

    @Override
    public Flux<StreamToken> streamTokens(
            NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx, String nodeId) {
        return WorkflowLlmStreamSupport.streamTokens(llmGateway, spec, ctx, streamCtx, nodeId)
                .onErrorResume(e -> {
                    log.warn("[LlmNodeHandler] 流式失败: {}", e.getMessage());
                    return Flux.error(e);
                });
    }

    @Override
    public NodeResult buildResult(WorkflowStreamCollector collector) {
        return WorkflowLlmStreamSupport.buildResult(collector);
    }
}
