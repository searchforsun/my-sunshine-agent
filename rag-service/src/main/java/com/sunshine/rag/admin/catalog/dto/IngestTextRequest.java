package com.sunshine.rag.admin.catalog.dto;

/** Admin 文本入库 */
public record IngestTextRequest(
        String content,
        String docId,
        String docName,
        String displayName) {
}
