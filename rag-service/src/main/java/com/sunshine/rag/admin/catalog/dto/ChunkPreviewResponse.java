package com.sunshine.rag.admin.catalog.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 分块预览响应 */
public record ChunkPreviewResponse(
        String previewId,
        String strategy,
        Map<String, Object> params,
        String contentHash,
        int chunkCount,
        List<ChunkPreviewChunkItem> chunks,
        Instant expiresAt) {
}
