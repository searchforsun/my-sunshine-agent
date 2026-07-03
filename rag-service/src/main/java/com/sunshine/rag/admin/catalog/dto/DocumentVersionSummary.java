package com.sunshine.rag.admin.catalog.dto;

/** 文档版本摘要 */
public record DocumentVersionSummary(
        String version,
        String status,
        int chunkCount,
        boolean hasContent,
        String publishedAt,
        String createdAt) {
}
