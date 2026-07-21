package com.sunshine.rag.admin.catalog.dto;

/** 文档版本摘要 */
public record DocumentVersionSummary(
        String version,
        String status,
        int chunkCount,
        boolean hasContent,
        boolean needsQuarantineConfirm,
        Long ingestJobId,
        String chunkStrategy,
        String publishedAt,
        String createdAt) {
}
