package com.sunshine.rag.admin.eval.dto;

/** 文本类优化建议（Prompt / 评测问句等，需人工采纳） */
public record TextSuggestionItem(
        String target,
        String kind,
        String current,
        String proposed,
        String reason) {
}
