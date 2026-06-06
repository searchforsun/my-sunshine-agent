package com.sunshine.rag.controller;

import com.sunshine.rag.parser.MarkdownParser;
import com.sunshine.rag.service.EmbeddingService;
import com.sunshine.rag.service.MilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * 文档入库控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class IngestionController {

    private final MarkdownParser parser;
    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;

    @PostMapping("/documents")
    public Mono<Map<String, Object>> ingest(@RequestBody Map<String, String> body) {
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            return Mono.just(Map.of("code", 400, "msg", "内容不能为空"));
        }

        List<String> chunks = parser.parse(content);
        log.info("[RAG] 文档入库: {} 个分段", chunks.size());

        return Flux.fromIterable(chunks)
                .flatMap(chunk ->
                        embeddingService.embed(chunk)
                                .doOnNext(vector -> milvusService.insert(chunk, vector))
                )
                .collectList()
                .map(vectors -> Map.of(
                        "code", 200,
                        "msg", "入库成功",
                        "chunks", (Object) chunks.size()
                ));
    }
}
