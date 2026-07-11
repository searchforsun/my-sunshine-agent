package com.sunshine.tool.admin.dto;

import java.util.List;

public record ToolSetToolIdsResponse(
        List<String> toolIds,
        List<String> criticalToolIds
) {
}
