package com.sunshine.common.tool.admin;

public record ToolSetMemberItemResponse(
        String toolId,
        String displayName,
        String description,
        String source,
        String sourceRef,
        String sourceLabel,
        String sideEffect,
        boolean critical,
        int sortOrder) {
}
