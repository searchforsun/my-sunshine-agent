package com.sunshine.tool.admin.dto;

import java.util.List;

public record ToolSetMemberAddResult(
        List<String> added,
        List<String> skipped,
        List<ToolSetMemberReject> rejected
) {
}
