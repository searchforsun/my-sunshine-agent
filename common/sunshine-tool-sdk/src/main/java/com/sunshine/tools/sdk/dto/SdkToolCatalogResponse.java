package com.sunshine.tools.sdk.dto;

import java.util.List;
import java.util.Map;

public record SdkToolCatalogResponse(
        String appId,
        String appVersion,
        int schemaVersion,
        List<ToolEntry> tools) {

    public record ToolEntry(
            String name,
            String displayName,
            String description,
            String sideEffect,
            String timelinePhase,
            String outputSummaryKind,
            Map<String, Object> parameters) {
    }
}
