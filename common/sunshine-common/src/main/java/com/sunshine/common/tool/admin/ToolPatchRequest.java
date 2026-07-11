package com.sunshine.common.tool.admin;

public record ToolPatchRequest(
        Boolean enabled,
        String displayName,
        String description,
        Boolean requireConfirmation,
        String timelineSummaryTemplate,
        String timelineSummaryExtract) {
}
