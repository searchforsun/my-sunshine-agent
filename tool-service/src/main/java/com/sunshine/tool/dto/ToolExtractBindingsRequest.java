package com.sunshine.tool.dto;

/** POST /api/tools/extract-bindings */
public record ToolExtractBindingsRequest(
        String extractJson,
        String text) {
}
