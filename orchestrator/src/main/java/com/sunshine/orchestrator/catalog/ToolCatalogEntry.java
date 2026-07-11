package com.sunshine.orchestrator.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * 工具目录条目 — 合并 tool-manager catalog 与本地 RagTool 元数据
 */
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
        boolean requireConfirmation
) {
}
