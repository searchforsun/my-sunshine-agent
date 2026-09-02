package com.sunshine.rag.model;

import java.util.List;

/** Milvus chunk 写入请求 — V2 metadata + 分块策略字段 */
public record ChunkInsertRequest(
        String docName,
        String content,
        List<Float> embedding,
        String tenantId,
        String kbId,
        String docId,
        String version,
        int chunkIndex,
        String status,
        String sourceType,
        String strategy,
        String chunkLevel,
        String parentChunkId) {

    public ChunkInsertRequest(
            String docName,
            String content,
            List<Float> embedding,
            String tenantId,
            String kbId,
            String docId,
            String version,
            int chunkIndex,
            String status,
            String sourceType) {
        this(docName, content, embedding, tenantId, kbId, docId, version, chunkIndex,
                status, sourceType, null, null, null);
    }
}
