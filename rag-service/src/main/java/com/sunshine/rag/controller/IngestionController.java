package com.sunshine.rag.controller;

import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.result.R;
import com.sunshine.rag.exception.RagErrorCode;
import com.sunshine.rag.parser.MarkdownParser;
import com.sunshine.rag.service.ElasticsearchIndexService;
import com.sunshine.rag.service.EmbeddingService;
import com.sunshine.rag.service.MilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    private static final String DEFAULT_DOC_NAME = "未命名文档";

    private final MarkdownParser parser;
    private final EmbeddingService embeddingService;
    private final MilvusService milvusService;
    private final ElasticsearchIndexService elasticsearchIndexService;

    @PostMapping("/documents")
    public Mono<R<Map<String, Object>>> ingest(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        String content = body.get("content");
        if (content == null || content.isBlank()) {
            throw new BizException(RagErrorCode.CONTENT_EMPTY);
        }

        String tid = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        String docName = resolveDocName(body, content);
        List<String> chunks = parser.parse(content);
        log.info("[RAG] 文档入库: tenant={}, docName='{}', {} 个分段", tid, docName, chunks.size());

        return Flux.fromIterable(chunks)
                .index()
                .flatMap(tuple -> {
                    long index = tuple.getT1();
                    String chunk = tuple.getT2();
                    String chunkId = docName + "#" + index;
                    return embeddingService.embed(chunk)
                            .doOnNext(vector -> {
                                milvusService.insert(docName, chunk, vector, tid);
                                elasticsearchIndexService.indexChunk(
                                        chunkId, docName, chunk, (int) index, tid);
                            });
                })
                .collectList()
                .map(vectors -> R.ok(Map.of(
                        "docName", docName,
                        "chunks", (Object) chunks.size()
                )));
    }

    private static String resolveDocName(Map<String, String> body, String content) {
        String docName = body.get("docName");
        if (docName == null || docName.isBlank()) {
            docName = body.get("title");
        }
        if (docName != null && !docName.isBlank()) {
            return docName.trim();
        }
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        return DEFAULT_DOC_NAME;
    }
}
