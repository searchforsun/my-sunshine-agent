package com.sunshine.rag.admin.catalog.dto;

import java.time.Instant;

/** 异步解析任务进度 */
public record DocumentParseJobStatus(
        Long jobId,
        String docId,
        String version,
        String status,
        Double progressPct,
        Integer progressPage,
        Integer totalPages,
        Double confidence,
        Boolean needsConfirm,
        String errorMsg,
        Instant updatedAt) {
}
