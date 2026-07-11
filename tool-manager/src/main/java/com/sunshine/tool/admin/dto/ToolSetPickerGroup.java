package com.sunshine.tool.admin.dto;

import java.util.List;

public record ToolSetPickerGroup(
        String source,
        String sourceRef,
        String title,
        List<ToolSetPickerToolItem> tools
) {
}
