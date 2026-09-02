package com.sunshine.rag.service;

import com.sunshine.rag.util.ChunkIds;
import com.sunshine.rag.util.DocumentVersionTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 按 chunk_id 拉取父块正文（Milvus 优先，ES 回退）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkContentLookup {

    private final MilvusService milvusService;
    private final ElasticsearchIndexService elasticsearchIndexService;

    public String fetchContent(String tenantId, String kbId, String chunkId) {
        ChunkIds.Parsed parsed = ChunkIds.parse(chunkId);
        if (parsed == null) {
            return null;
        }
        String fromMilvus = milvusService.queryChunkContent(
                tenantId, kbId, parsed.docId(), parsed.version(), parsed.index());
        if (fromMilvus != null && !fromMilvus.isBlank()) {
            return fromMilvus;
        }
        return elasticsearchIndexService.fetchContentByChunkId(chunkId);
    }
}
