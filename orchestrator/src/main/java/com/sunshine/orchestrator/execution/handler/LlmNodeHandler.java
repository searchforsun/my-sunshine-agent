package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 节点 — 同步补全，结果写入上下文供下游 answer 节点流式输出
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmNodeHandler implements NodeHandler {

    private final LlmGatewayClient llmGateway;

    @Override
    public String type() {
        return "llm";
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        String prompt = spec.params().getOrDefault("prompt", "");
        return Mono.fromCallable(() -> llmGateway.complete(null, prompt))
                .subscribeOn(Schedulers.boundedElastic())
                .map(answer -> {
                    String text = answer != null ? answer : "";
                    Map<String, String> outputs = new LinkedHashMap<>();
                    outputs.put("answer", text);
                    outputs.put("output", text);
                    outputs.put("detail", text.isBlank()
                            ? "未生成内容"
                            : "已完成回复");
                    return NodeResult.ok(outputs);
                })
                .onErrorResume(e -> {
                    log.warn("[LlmNodeHandler] 补全失败: {}", e.getMessage());
                    return Mono.just(NodeResult.fail(e.getMessage()));
                });
    }
}
