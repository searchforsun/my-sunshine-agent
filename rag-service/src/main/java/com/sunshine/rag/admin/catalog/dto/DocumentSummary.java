package com.sunshine.rag.admin.catalog.dto;

/** 文档列表项 */
public record DocumentSummary(
        String docId,
        String displayName,
        String sourceType,
        String activeVersion,
        int chunkCount) {
}
