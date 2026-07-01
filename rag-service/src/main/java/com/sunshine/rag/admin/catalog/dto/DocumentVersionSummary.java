package com.sunshine.rag.admin.catalog.dto;

/** 文档版本摘要 */
public record DocumentVersionSummary(
        int version,
        String status,
        int chunkCount,
        String publishedAt) {
}
