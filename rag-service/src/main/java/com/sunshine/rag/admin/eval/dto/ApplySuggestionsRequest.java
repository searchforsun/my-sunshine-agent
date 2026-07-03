package com.sunshine.rag.admin.eval.dto;

import java.util.List;

public record ApplySuggestionsRequest(List<ConfigSuggestionItem> suggestions, Long versionId) {
}
