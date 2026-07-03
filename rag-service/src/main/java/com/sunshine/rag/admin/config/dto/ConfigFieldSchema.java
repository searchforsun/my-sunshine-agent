package com.sunshine.rag.admin.config.dto;

import java.util.List;

/** 单条可配参数（schema SSOT，前端勿硬编码默认值） */
public record ConfigFieldSchema(
        String fieldId,
        String label,
        String type,
        Object min,
        Object max,
        String scope,
        Object currentValue,
        List<String> enumValues) {
}
