package com.sunshine.tool.admin.dto;

import java.util.List;

public record ToolSetMembersPageResponse(
        int page,
        int size,
        long total,
        List<ToolSetMemberItemResponse> items
) {
}
