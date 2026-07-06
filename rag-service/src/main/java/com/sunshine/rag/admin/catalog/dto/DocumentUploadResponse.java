package com.sunshine.rag.admin.catalog.dto;

/** 文档上传响应：md/txt 同步完成；pdf/docx 异步解析 */
public record DocumentUploadResponse(
        boolean async,
        Long jobId,
        String version,
        String status,
        Double progressPct,
        String content,
        String storagePath) {
}
