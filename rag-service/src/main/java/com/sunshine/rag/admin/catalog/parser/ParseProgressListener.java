package com.sunshine.rag.admin.catalog.parser;

/** PDF/DOCX 异步解析进度回调 */
@FunctionalInterface
public interface ParseProgressListener {
    void onProgress(int currentPage, int totalPages, double progressPct);
}
