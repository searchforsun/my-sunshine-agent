package com.sunshine.rag.admin.eval.dto;

public record EvalInternalRunRequest(
        String query,
        String kbId,
        Integer topK,
        String strategy,
        String configMode,
        Long configVersionId) {
}
