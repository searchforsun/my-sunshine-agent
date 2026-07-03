package com.sunshine.rag.admin.eval.dto;

import java.util.List;

public record FailedEvalSample(
        String queryId,
        String query,
        List<String> expectedDocNames,
        List<String> topDocNames) {
}
