package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.PromptOverlayProperties;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.StreamingNodeHandler;
import com.sunshine.orchestrator.execution.WorkflowContext;
import com.sunshine.orchestrator.execution.WorkflowStreamCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/** 终态 answer 节点 — 仅 content 流式正文；上游数据已在 nodePrompt 注入 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnswerNodeHandler implements StreamingNodeHandler {

    private final LlmGatewayClient llmGateway;
    private final PromptOverlayProperties overlayProperties;

    @Override
    public String type() {
        return "answer";
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        if (!WorkflowLlmStreamSupport.hasNodePrompt(spec)) {
            return Mono.just(passThrough(spec, ctx));
        }
        WorkflowStreamCollector collector = new WorkflowStreamCollector();
        return streamTokens(spec, ctx, streamCtx, spec.id())
                .doOnNext(collector::accept)
                .then(Mono.fromSupplier(() -> buildResult(collector)))
                .onErrorResume(e -> {
                    log.warn("[AnswerNodeHandler] 流式失败: {}", e.getMessage());
                    return Mono.just(NodeResult.fail(e.getMessage()));
                });
    }

    @Override
    public Flux<StreamToken> streamTokens(
            NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx, String nodeId) {
        if (!WorkflowLlmStreamSupport.hasNodePrompt(spec)) {
            return Flux.empty();
        }
        return WorkflowLlmStreamSupport.streamTokens(
                        llmGateway, spec, ctx, streamCtx, nodeId, true, overlayProperties.getAnswerOverlay())
                .onErrorResume(e -> {
                    log.warn("[AnswerNodeHandler] 流式失败: {}", e.getMessage());
                    return Flux.error(e);
                });
    }

    @Override
    public NodeResult buildResult(WorkflowStreamCollector collector) {
        return WorkflowLlmStreamSupport.buildResult(collector, true);
    }

    private static NodeResult passThrough(NodeSpec spec, WorkflowContext ctx) {
        String answer = resolveUpstreamAnswer(spec, ctx);
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("answer", answer);
        outputs.put("output", answer);
        outputs.put("detail", answer);
        return NodeResult.ok(outputs);
    }

    private static String resolveUpstreamAnswer(NodeSpec spec, WorkflowContext ctx) {
        String fromParam = spec.params().get("from");
        if (StringUtils.hasText(fromParam)) {
            String direct = ctx.resolvePath(fromParam.strip());
            if (StringUtils.hasText(direct)) {
                return direct;
            }
        }
        String last = "";
        for (Map.Entry<String, Map<String, String>> entry : ctx.nodeEntries()) {
            Map<String, String> node = entry.getValue();
            if (node.containsKey("answer") && StringUtils.hasText(node.get("answer"))) {
                last = node.get("answer");
            } else if (node.containsKey("output") && StringUtils.hasText(node.get("output"))) {
                last = node.get("output");
            }
        }
        return last;
    }
}
