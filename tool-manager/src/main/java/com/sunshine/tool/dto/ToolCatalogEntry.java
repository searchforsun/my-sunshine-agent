package com.sunshine.tool.dto;

import java.util.Map;

/**
 * 工具目录条目 — orchestrator 拉取后用于展示名、时间线摘要与 Agent schema
 */
public record ToolCatalogEntry(
        String id,
        String displayName,
        String description,
        String kind,
        String source,
        String sourceRef,
        String timelineSummaryTemplate,
        String timelineSummaryExtract,
        Map<String, Object> parameters,
        String sideEffect,
        boolean requireConfirmation,
        boolean enabled,
        boolean idValid,
        String idError
) {
}
