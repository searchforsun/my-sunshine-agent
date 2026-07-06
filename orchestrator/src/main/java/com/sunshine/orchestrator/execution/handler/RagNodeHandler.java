package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.client.RagClient;
import com.sunshine.orchestrator.client.RagContextFormatter;
import com.sunshine.orchestrator.client.RagDetailFormatter;
import com.sunshine.orchestrator.rag.KnowledgeRetrievalService;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索节点 — 节点 params.topK 可覆盖；未配置时走 rag-service Nacos default-top-k。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagNodeHandler implements NodeHandler {

    private final KnowledgeRetrievalService knowledgeRetrievalService;

    @Override
    public String type() {
        return "rag";
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        String query = ctx.resolvePath("start.userQuery");
        if (query.isBlank()) {
            query = streamCtx.userContent();
        }
        Integer topK = parseTopK(spec.params().get("topK"));
        String kbId = spec.params().get("kbId");
        if (kbId == null || kbId.isBlank()) {
            kbId = streamCtx.kbId();
        }
        String finalQuery = query;
        String finalKbId = kbId;

        return knowledgeRetrievalService.searchMono(
                        finalQuery, topK, finalKbId, streamCtx.tenantId(), streamCtx.assistantMsgId())
                .map(hits -> {
                    List<RagClient.RagHit> results = hits != null ? hits : List.of();
                    String output = RagContextFormatter.formatAgentContext(results);
                    String detail = RagDetailFormatter.formatDetail(results);
                    Map<String, String> outputs = new LinkedHashMap<>();
                    outputs.put("output", output);
                    outputs.put("hitCount", String.valueOf(results.size()));
                    outputs.put("detail", detail);
                    return NodeResult.ok(outputs);
                })
                .onErrorResume(e -> {
                    log.warn("[RagNodeHandler] 检索失败: {}", e.getMessage());
                    Map<String, String> outputs = new LinkedHashMap<>();
                    outputs.put("output", RagContextFormatter.formatAgentContext(List.of()));
                    outputs.put("hitCount", "0");
                    outputs.put("detail", "命中 0 条");
                    return Mono.just(NodeResult.ok(outputs));
                });
    }

    private static Integer parseTopK(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
