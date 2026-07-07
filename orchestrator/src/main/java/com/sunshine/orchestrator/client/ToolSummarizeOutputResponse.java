package com.sunshine.orchestrator.client;

/** tool-manager POST /api/tools/summarize-output 响应 */
public record ToolSummarizeOutputResponse(
        String summary,
        boolean zeroHit,
        boolean empty) {
}
