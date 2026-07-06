package com.sunshine.rag.admin.catalog.dto;

/** 新建文档元数据 */
public record CreateDocumentRequest(String docId, String displayName, String sourceType) {
}
