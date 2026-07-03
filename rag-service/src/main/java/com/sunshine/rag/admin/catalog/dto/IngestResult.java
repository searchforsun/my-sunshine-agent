package com.sunshine.rag.admin.catalog.dto;

/** 入库结果 */
public record IngestResult(
        String docId,
        String docName,
        String version,
        int chunks) {
}
