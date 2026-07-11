package com.sunshine.tool.admin.dto;

public record ToolPatchRequest(
        Boolean enabled,
        String displayName,
        String description,
        Boolean requireConfirmation,
        String timelineSummaryTemplate,
        String timelineSummaryExtract) {
}
