package com.sunshine.rag.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.result.R;
import com.sunshine.rag.exception.RagErrorCode;
import com.sunshine.rag.service.ToolIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具语义索引端点 — orchestrator 全量同步工具目录 + 每轮检索 Top-K 工具。
 */
@Slf4j
@RestController
@RequestMapping("/api/tool-index")
@RequiredArgsConstructor
public class ToolIndexController {

    private final ToolIndexService toolIndexService;

    /** 全量重建工具索引（幂等；工具目录变化后由 orchestrator 调用）。 */
    @PostMapping("/sync")
    public Mono<R<Void>> sync(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        String tid = resolveTenantId(body.get("tenantId"), tenantId);
        List<ToolIndexDocPayload> tools = parseTools(body.get("tools"));
        return toolIndexService.sync(tid, tools.stream()
                        .map(doc -> new ToolIndexService.ToolIndexDoc(
                                doc.toolId(), doc.name(), doc.description(), doc.paramsSummary()))
                        .toList())
                .thenReturn(R.ok(null));
    }

    /** query → Top-K 工具命中（分数降序）。 */
    @PostMapping("/search")
    public Mono<R<List<Map<String, Object>>>> search(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        String query = (String) body.get("query");
        if (query == null || query.isBlank()) {
            throw new BizException(RagErrorCode.QUERY_EMPTY);
        }
        String tid = resolveTenantId(body.get("tenantId"), tenantId);
        Integer topK = body.get("topK") instanceof Number n ? n.intValue() : null;
        Float minScore = body.get("minScore") instanceof Number num ? num.floatValue() : null;
        return toolIndexService.search(query, topK, tid, minScore)
                .map(hits -> hits.stream()
                        .map(hit -> {
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("toolId", hit.toolId());
                            item.put("score", hit.score());
                            return item;
                        })
                        .toList())
                .map(R::ok);
    }

    @SuppressWarnings("unchecked")
    private static List<ToolIndexDocPayload> parseTools(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<ToolIndexDocPayload> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) map;
            Object toolId = m.get("toolId");
            if (toolId == null || String.valueOf(toolId).isBlank()) {
                continue;
            }
            out.add(new ToolIndexDocPayload(
                    String.valueOf(toolId).strip(),
                    m.get("name") != null ? String.valueOf(m.get("name")) : null,
                    m.get("description") != null ? String.valueOf(m.get("description")) : null,
                    m.get("paramsSummary") != null ? String.valueOf(m.get("paramsSummary")) : null));
        }
        return out;
    }

    private static String resolveTenantId(Object bodyTenant, String headerTenant) {
        if (bodyTenant != null && !String.valueOf(bodyTenant).isBlank()) {
            return String.valueOf(bodyTenant).strip();
        }
        if (headerTenant != null && !headerTenant.isBlank()) {
            return headerTenant.strip();
        }
        return "default";
    }

    private record ToolIndexDocPayload(String toolId, String name, String description, String paramsSummary) {
    }
}
