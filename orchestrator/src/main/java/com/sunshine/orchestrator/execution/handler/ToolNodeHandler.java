package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.client.ToolManagerClient;
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
 * 工具调用节点 — 委托 tool-manager，params 统一透传
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolNodeHandler implements NodeHandler {

    private final ToolManagerClient toolManagerClient;
    private final ToolCatalogService toolCatalogService;

    @Override
    public String type() {
        return "tool";
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        String tool = spec.params().getOrDefault("tool", "");
        Map<String, String> invokeParams = new LinkedHashMap<>();
        spec.params().forEach((k, v) -> {
            if (!"tool".equals(k)) {
                invokeParams.put(k, v);
            }
        });

        return Mono.fromCallable(() -> toolManagerClient.invoke(tool, invokeParams))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> {
                    String text = result != null ? result : "";
                    Map<String, String> outputs = new LinkedHashMap<>();
                    outputs.put("output", text);
                    outputs.put("tool", tool);
                    outputs.put("detail", summarizeToolOutput(tool, text));
                    return NodeResult.ok(outputs);
                })
                .onErrorResume(e -> {
                    log.warn("[ToolNodeHandler] 工具 {} 失败: {}", tool, e.getMessage());
                    return Mono.just(NodeResult.fail(e.getMessage()));
                });
    }

    private String summarizeToolOutput(String tool, String text) {
        if (text == null || text.isBlank()) {
            return "未返回数据";
        }
        return toolCatalogService.summarizeOutput(tool, text);
    }
}
