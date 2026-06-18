package com.sunshine.orchestrator.catalog;

import java.util.Map;

/**
 * 工具目录条目 — 合并 tool-manager catalog 与本地 RagTool 元数据
 */
public record ToolCatalogEntry(
        String id,
        String displayName,
        String description,
        String kind,
        String timelinePhase,
        String outputSummaryKind,
        Map<String, Object> parameters
) {
}
