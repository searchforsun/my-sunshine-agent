package com.sunshine.tool.dto;

/** 工具输出一步摘要 */
public record ToolSummarizeOutputResponse(
        String summary,
        boolean zeroHit,
        boolean empty) {
}
