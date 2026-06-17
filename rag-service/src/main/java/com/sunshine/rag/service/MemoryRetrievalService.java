package com.sunshine.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryRetrievalService {

    private final EmbeddingService embeddingService;
    private final MemoryMilvusService memoryMilvusService;

    public Mono<List<MemoryMilvusService.MemoryHit>> search(
            String userId, String tenantId, String query, int topK) {
        return embeddingService.embed(query)
                .map(vector -> memoryMilvusService.search(userId, tenantId, vector, topK))
                .doOnSuccess(h -> log.info("[MTM] 情景召回 user={} hits={}", userId, h.size()));
    }

    public Mono<Void> upsert(String userId, String tenantId, String convId, String summary) {
        return embeddingService.embed(summary)
                .doOnNext(vector -> memoryMilvusService.upsert(userId, tenantId, convId, summary, vector))
                .then();
    }
}
