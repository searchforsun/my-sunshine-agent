package com.sunshine.rag.admin.catalog.dto;

/** 知识库列表项 */
public record KbSummary(
        String kbId,
        String displayName,
        String description,
        boolean isDefault,
        String status) {
}
