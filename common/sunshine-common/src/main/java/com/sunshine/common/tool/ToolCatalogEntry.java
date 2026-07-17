package com.sunshine.common.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/** 工具目录条目 SSOT — tool-manager / orchestrator / BFF 共用 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
        String idError) {
}
