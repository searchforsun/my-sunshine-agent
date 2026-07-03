package com.sunshine.rag.admin.eval.dto;

public record EvalSuggestRequest(long reportId, String kbId, Boolean regenerate) {
}
