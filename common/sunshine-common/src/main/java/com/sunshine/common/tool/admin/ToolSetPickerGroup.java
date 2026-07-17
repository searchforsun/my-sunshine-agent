package com.sunshine.common.tool.admin;

import java.util.List;

public record ToolSetPickerGroup(
        String source,
        String sourceRef,
        String title,
        List<ToolSetPickerToolItem> tools) {
}
