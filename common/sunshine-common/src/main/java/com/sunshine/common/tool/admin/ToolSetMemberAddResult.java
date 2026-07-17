package com.sunshine.common.tool.admin;

import java.util.List;

public record ToolSetMemberAddResult(
        List<String> added,
        List<String> skipped,
        List<ToolSetMemberReject> rejected) {
}
