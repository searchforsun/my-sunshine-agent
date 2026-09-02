package com.sunshine.rag.admin.catalog.dto;

import java.util.Map;

/** 分块预览条目 */
public record ChunkPreviewChunkItem(
        int index,
        String text,
        int charCount,
        Map<String, Object> meta) {
}
