package com.sunshine.rag.admin.catalog.dto;

/** 新建知识库请求 */
public record CreateKbRequest(
        String kbId,
        String displayName,
        String description) {
}
