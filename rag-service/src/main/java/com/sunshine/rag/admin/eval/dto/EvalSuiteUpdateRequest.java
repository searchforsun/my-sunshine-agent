package com.sunshine.rag.admin.eval.dto;

import java.util.Map;

/** 更新评测集元数据与公共配置 */
public record EvalSuiteUpdateRequest(
        String displayName,
        String description,
        Map<String, Object> config,
        Map<String, Object> hooks) {
}
