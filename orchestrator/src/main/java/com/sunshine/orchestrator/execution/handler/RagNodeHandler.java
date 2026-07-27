package com.sunshine.orchestrator.execution.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.client.RagClient;
import com.sunshine.orchestrator.client.RagContextFormatter;
import com.sunshine.orchestrator.execution.TypedValue;
import com.sunshine.orchestrator.execution.WorkflowNodeCompletionLabels;
import com.sunshine.orchestrator.execution.WorkflowNodeTimeline;
import com.sunshine.common.workflow.WorkflowNodeType;
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
 * RAG 检索节点 - params.query / context 与 agent 同契约；topK、kbId 可覆盖默认值。
 * 输出新增结构化 hits（ArrayNode TypedValue），保留 output（格式化文本）与 hitCount。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagNodeHandler implements NodeHandler {

    private static final ObjectMapper OM = new ObjectMapper();

    private final RagClient ragClient;
    private final DefaultKbResolver defaultKbResolver;

    @Override
    public String type() {
        return WorkflowNodeType.RAG.id();
    }

    @Override
    public Mono<NodeResult> run(NodeSpec spec, WorkflowContext ctx, ExecutionStreamContext streamCtx) {
        Map<String, Object> params = spec.params() != null ? spec.params() : Map.of();
        String query = readParamString(params, "query");
        if (!StringUtils.hasText(query)) {
            query = ctx.resolvePathString("start.userQuery");
        }
        if (!StringUtils.hasText(query)) {
            query = streamCtx.userContent();
        }
        String searchQuery = buildSearchQuery(query, readParamString(params, "context"));
        Integer topK = parseTopK(readParamString(params, "topK"));
        String kbId = readParamString(params, "kbId");
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
        Map<String, TypedValue> outputs = new LinkedHashMap<>();
        outputs.put("output", TypedValue.scalar(RagContextFormatter.formatAgentContext(List.of())));
        outputs.put("hits", TypedValue.fromJson(OM.createArrayNode()));
        outputs.put("hitCount", TypedValue.scalar("0"));
        outputs.put("detail", TypedValue.scalar(WorkflowNodeCompletionLabels.hitCount("0")));
        return NodeResult.ok(outputs);
    }

    private static NodeResult buildOkResult(List<RagClient.RagHit> results) {
        Map<String, TypedValue> outputs = new LinkedHashMap<>();
        outputs.put("output", TypedValue.scalar(RagContextFormatter.formatAgentContext(results)));
        outputs.put("hits", TypedValue.fromJson(buildHitsArray(results)));
        outputs.put("hitCount", TypedValue.scalar(String.valueOf(results.size())));
        outputs.put("detail", TypedValue.scalar(WorkflowNodeCompletionLabels.hitCount(String.valueOf(results.size()))));
        return NodeResult.ok(outputs);
    }

    /** 测试可见：构造结构化 hits 结果（供单测断言 ArrayNode TypedValue） */
    static NodeResult buildOkResultForTest(List<RagClient.RagHit> results) {
        return buildOkResult(results != null ? results : List.of());
    }

    private static JsonNode buildHitsArray(List<RagClient.RagHit> results) {
        var arr = OM.createArrayNode();
        if (results == null) {
            return arr;
        }
        for (RagClient.RagHit hit : results) {
            var obj = arr.addObject();
            obj.put("docName", hit.docName());
            obj.put("content", hit.content());
            obj.put("score", hit.score());
        }
        return arr;
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

    private static String readParamString(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object v = params.get(key);
        return v != null ? v.toString() : null;
    }
}
