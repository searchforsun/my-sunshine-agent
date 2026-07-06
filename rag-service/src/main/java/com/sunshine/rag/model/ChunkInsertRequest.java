package com.sunshine.rag.model;

import java.util.List;

/** Milvus chunk 写入请求 — V2 metadata */
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
        String sourceType) {
}
