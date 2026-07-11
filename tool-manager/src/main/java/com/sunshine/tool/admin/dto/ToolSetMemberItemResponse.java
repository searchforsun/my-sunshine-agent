package com.sunshine.tool.admin.dto;

import java.util.List;

public record ToolSetMemberItemResponse(
        String toolId,
        String displayName,
        String description,
        String source,
        String sourceRef,
        String sourceLabel,
        String sideEffect,
        boolean critical,
        int sortOrder
) {
}
