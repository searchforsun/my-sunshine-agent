package com.sunshine.tool.dto;

/** POST /api/tools/summarize-output */
public record ToolSummarizeOutputRequest(
        String toolName,
        String text) {
}
