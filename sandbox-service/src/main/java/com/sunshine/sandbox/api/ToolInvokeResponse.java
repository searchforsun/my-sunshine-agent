package com.sunshine.sandbox.api;

import java.util.Map;

public record ToolInvokeResponse(
        boolean ok,
        String output,
        Integer exitCode,
        Map<String, Object> meta) {}
