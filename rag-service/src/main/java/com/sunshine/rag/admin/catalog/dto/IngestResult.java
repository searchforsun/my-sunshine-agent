package com.sunshine.rag.admin.catalog.dto;

/** 入库结果 */
public record IngestResult(
        String docId,
        String docName,
        int version,
        int chunks) {
}
