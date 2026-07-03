package com.sunshine.rag.admin.eval.dto;

import java.util.List;

/** 评测集条目增删改 */
public record EvalSuiteQueryRequest(
        /** add | update | delete */
        String action,
        String id,
        String query,
        List<String> relevantDocIds,
        String category) {
}
