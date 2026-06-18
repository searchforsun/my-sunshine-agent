package com.sunshine.tool.dto;

import java.util.Map;

/**
 * 工具目录条目 — orchestrator 拉取后用于展示名、Timeline phase 与 Agent schema
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
