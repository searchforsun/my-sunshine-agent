package com.sunshine.rag.admin.eval.dto;

public record ConfigSuggestionItem(
        String path,
        Object current,
        Object proposed,
        String reason) {
}
