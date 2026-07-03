package com.sunshine.rag.admin.eval.dto;

import java.util.Map;

/** 新建标准化评测集 */
public record EvalSuiteCreateRequest(
        String suiteKey,
        String displayName,
        String description,
        String kind,
        Map<String, Object> config,
        Map<String, Object> hooks,
        String content) {
}
