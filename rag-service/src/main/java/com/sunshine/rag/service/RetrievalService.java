package com.sunshine.rag.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * RAG 检索编排服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalService {

    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;

    public Mono<List<DocFragment>> search(String query, int topK) {
        log.info("[RAG] 检索: query='{}', topK={}", query, topK);

        return embeddingService.embed(query)
                .map(vector -> {
                    List<String> contents = milvusService.search(vector, topK);
                    return contents.stream()
                            .map(c -> new DocFragment(c, 0.0f))
                            .toList();
                })
                .doOnSuccess(r -> log.info("[RAG] 检索完成: {} 条结果", r.size()));
    }

    public record DocFragment(String content, float score) {
    }
}
