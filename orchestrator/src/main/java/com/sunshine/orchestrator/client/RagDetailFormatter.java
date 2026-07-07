package com.sunshine.orchestrator.client;

import java.util.List;

/** RAG 检索结果摘要 — 委托 tool-manager */
public final class RagDetailFormatter {

    private RagDetailFormatter() {
    }

    public static String formatDetail(List<RagClient.RagHit> hits, ToolManagerClient toolManagerClient) {
        ToolSummarizeOutputResponse response = toolManagerClient.summarizeRagHits(hits);
        return response != null ? response.summary() : "";
    }
}
