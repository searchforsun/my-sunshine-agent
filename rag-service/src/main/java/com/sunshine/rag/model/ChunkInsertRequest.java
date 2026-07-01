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
        int version,
        int chunkIndex,
        String status,
        String sourceType) {

    /** 兼容旧 IngestionController：kb=default, docId=docName, v1, active, markdown */
    public static ChunkInsertRequest legacyMarkdown(
            String docName, String content, List<Float> embedding, String tenantId, int chunkIndex) {
        String tid = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        return new ChunkInsertRequest(
                docName, content, embedding, tid,
                "default", docName, 1, chunkIndex, "active", "markdown");
    }
}
