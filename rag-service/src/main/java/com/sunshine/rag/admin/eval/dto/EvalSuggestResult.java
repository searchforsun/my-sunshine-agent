package com.sunshine.rag.admin.eval.dto;

import java.util.List;

public record EvalSuggestResult(
        String diagnosis,
        List<ConfigSuggestionItem> suggestions,
        List<TextSuggestionItem> textSuggestions) {

    public EvalSuggestResult {
        if (suggestions == null) {
            suggestions = List.of();
        }
        if (textSuggestions == null) {
            textSuggestions = List.of();
        }
    }
}
