package com.sunshine.tool.dto;

/** RAG 命中条目 — summarize-rag-hits 请求体 */
public record RagHitDto(String docName, String content) {
}
