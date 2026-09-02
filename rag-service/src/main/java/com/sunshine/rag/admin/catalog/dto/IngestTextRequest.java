package com.sunshine.rag.admin.catalog.dto;

import java.util.Map;

/** Admin 文本入库 */
public record IngestTextRequest(
        String content,
        String docId,
        String docName,
        String displayName,
        String strategy,
        Map<String, Object> params) {
}
