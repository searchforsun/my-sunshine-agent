package com.sunshine.rag.admin.eval.dto;

import java.util.List;
import java.util.Map;

public record EvalSuiteItemView(
        String itemKey,
        int sortOrder,
        String queryText,
        String itemType,
        List<String> relevantDocIds,
        List<String> relevantKeywords,
        String category,
        boolean expectEmpty) {
}
