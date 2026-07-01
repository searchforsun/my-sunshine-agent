package com.sunshine.rag.admin.catalog.dto;

/** chunk 预览 */
public record ChunkPreviewDto(
        int chunkIndex,
        String docName,
        String content) {
}
