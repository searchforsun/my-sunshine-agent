package com.sunshine.tool.dto;

import java.util.List;

/** POST /api/tools/summarize-rag-hits */
public record ToolSummarizeRagHitsRequest(List<RagHitDto> hits) {
}
