package com.sunshine.rag.admin.eval.dto;

import java.util.Map;

public record EvalSuiteDetail(
        long id,
        String suiteKey,
        String displayName,
        String description,
        String kind,
        String format,
        String contentRef,
        Map<String, Object> hooks,
        Map<String, Object> config,
        int itemCount,
        String status,
        boolean builtin,
        String content,
        java.util.List<EvalSuiteItemView> items) {
}
