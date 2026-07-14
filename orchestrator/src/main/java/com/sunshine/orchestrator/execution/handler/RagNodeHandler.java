package com.sunshine.orchestrator.execution.handler;

import com.sunshine.orchestrator.client.RagClient;
import com.sunshine.orchestrator.client.RagContextFormatter;
import com.sunshine.orchestrator.execution.WorkflowNodeCompletionLabels;
import com.sunshine.orchestrator.execution.WorkflowNodeTimeline;
import com.sunshine.orchestrator.execution.WorkflowNodeType;
import com.sunshine.orchestrator.rag.DefaultKbResolver;
import com.sunshine.orchestrator.rag.RagSearch;
import com.sunshine.orchestrator.execution.ExecutionStreamContext;
import com.sunshine.orchestrator.execution.NodeHandler;
import com.sunshine.orchestrator.execution.NodeResult;
import com.sunshine.orchestrator.execution.NodeSpec;
import com.sunshine.orchestrator.execution.WorkflowContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索节点 — params.query / context 与 agent 同契约；topK、kbId 可覆盖默认值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagNodeHandler implements NodeHandler {

    private final RagClient ragClient;
    private final DefaultKbResolver defaultKbResolver;

    @Override
    public String type() {
        return WorkflowNodeType.RAG.id();
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        Map<String, String> params = spec.params() != null ? spec.params() : Map.of();
        String query = params.getOrDefault("query", "");
        if (!StringUtils.hasText(query)) {
            query = ctx.resolvePath("start.userQuery");
        }
        if (!StringUtils.hasText(query)) {
            query = streamCtx.userContent();
        }
        String searchQuery = buildSearchQuery(query, params.get("context"));
        Integer topK = parseTopK(params.get("topK"));
        String kbId = params.get("kbId");
        if (kbId == null || kbId.isBlank()) {
            kbId = streamCtx.kbId();
        }
        String finalQuery = searchQuery;
        String finalKbId = kbId;
        String ragStepId = WorkflowNodeTimeline.stepId(spec.id());

        return RagSearch.searchMono(
                        ragClient,
                        defaultKbResolver,
                        finalQuery,
                        topK,
                        finalKbId,
                        streamCtx.tenantId(),
                        streamCtx.assistantMsgId(),
                        ragStepId)
                .flatMap(hits -> {
                    List<RagClient.RagHit> results = hits != null ? hits : List.of();
                    return Mono.just(buildOkResult(results));
                })
                .onErrorResume(e -> {
                    log.warn("[RagNodeHandler] 检索失败: {}", e.getMessage());
                    return Mono.just(buildEmptyResult());
                });
    }

    /** query 为主检索问句；context 非空时追加到检索文本（与 agent 注入语义一致） */
    static String buildSearchQuery(String query, String context) {
        String q = query != null ? query.strip() : "";
        String c = context != null ? context.strip() : "";
        if (!StringUtils.hasText(c)) {
            return q;
        }
        if (!StringUtils.hasText(q)) {
            return c;
        }
        return q + "\n\n" + c;
    }

    private static NodeResult buildEmptyResult() {
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("output", RagContextFormatter.formatAgentContext(List.of()));
        outputs.put("hitCount", "0");
        outputs.put("detail", WorkflowNodeCompletionLabels.hitCount("0"));
        return NodeResult.ok(outputs);
    }

    private static NodeResult buildOkResult(List<RagClient.RagHit> results) {
        Map<String, String> outputs = new LinkedHashMap<>();
        outputs.put("output", RagContextFormatter.formatAgentContext(results));
        outputs.put("hitCount", String.valueOf(results.size()));
        outputs.put("detail", WorkflowNodeCompletionLabels.hitCount(String.valueOf(results.size())));
        return NodeResult.ok(outputs);
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
