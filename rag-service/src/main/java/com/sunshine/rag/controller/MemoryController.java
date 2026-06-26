package com.sunshine.rag.controller;

import com.sunshine.common.core.result.R;
import com.sunshine.rag.service.MemoryMilvusService;
import com.sunshine.rag.service.MemoryRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/rag/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final MemoryRetrievalService memoryRetrievalService;

    @PostMapping("/search")
    public Mono<R<Map<String, Object>>> search(@RequestBody Map<String, Object> body) {
        String userId = (String) body.get("userId");
        String tenantId = body.containsKey("tenantId") ? (String) body.get("tenantId") : "default";
        String query = (String) body.get("query");
        int topK = body.containsKey("topK") ? ((Number) body.get("topK")).intValue() : 3;

        return memoryRetrievalService.search(userId, tenantId, query, topK)
                .map(hits -> R.ok(Map.of(
                        "results", (Object) hits.stream().map(MemoryController::toMap).toList())));
    }

    @PostMapping("/upsert")
    public Mono<R<Map<String, Object>>> upsert(@RequestBody Map<String, Object> body) {
        String userId = (String) body.get("userId");
        String tenantId = body.containsKey("tenantId") ? (String) body.get("tenantId") : "default";
        String convId = (String) body.get("convId");
        String summary = (String) body.get("summary");

        return memoryRetrievalService.upsert(userId, tenantId, convId, summary)
                .thenReturn(R.ok(Map.of("convId", convId)));
    }

    private static Map<String, Object> toMap(MemoryMilvusService.MemoryHit hit) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("convId", hit.convId());
        item.put("summary", hit.summary());
        item.put("score", hit.score());
        return item;
    }
}
