package com.sunshine.rag.controller;

import com.sunshine.rag.service.RetrievalService;
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
 * 检索控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RetrievalController {

    private final RetrievalService retrievalService;

    @PostMapping("/search")
    public Mono<Map<String, Object>> search(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        String query = (String) body.get("query");
        int topK = body.containsKey("topK")
                ? ((Number) body.get("topK")).intValue()
                : 5;
        String tid = resolveTenantId(body.get("tenantId"), tenantId);

        return retrievalService.search(query, topK, (String) body.get("strategy"), tid)
                .map(fragments -> Map.of(
                        "code", 200,
                        "query", query,
                        "results", (Object) fragments.stream()
                                .map(RetrievalController::toResultMap)
                                .toList()
                ));
    }

    private static Map<String, Object> toResultMap(RetrievalService.DocFragment fragment) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("docName", fragment.docName());
        item.put("content", fragment.content());
        item.put("score", fragment.score());
        return item;
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
}
