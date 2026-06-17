package com.sunshine.tool.dto;

import java.util.Map;

public record ToolInvokeRequest(String name, Map<String, String> params) {
}
