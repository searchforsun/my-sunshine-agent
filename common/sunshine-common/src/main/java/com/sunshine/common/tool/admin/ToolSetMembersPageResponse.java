package com.sunshine.common.tool.admin;

import java.util.List;

public record ToolSetMembersPageResponse(
        int page,
        int size,
        long total,
        List<ToolSetMemberItemResponse> items) {
}
