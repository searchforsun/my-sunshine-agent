package com.sunshine.common.sandbox;

import java.util.Map;

public record ToolInvokeResponse(
        boolean ok,
        String output,
        Integer exitCode,
        Map<String, Object> meta) {
}
