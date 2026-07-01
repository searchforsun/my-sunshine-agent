package com.sunshine.rag.pipeline;

/** Pipeline 检索请求 */
public record PipelineSearchRequest(
        String query,
        int topK,
        String tenantId,
        String kbId,
        String strategy,
        boolean rewrite,
        boolean includeTrace) {

    public static PipelineSearchRequest of(
            String query, int topK, String tenantId, String kbId, String strategy,
            Boolean rewrite, Boolean includeTrace) {
        String tid = tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default";
        String kid = kbId != null && !kbId.isBlank() ? kbId.strip() : "default";
        return new PipelineSearchRequest(
                query != null ? query.strip() : "",
                topK,
                tid,
                kid,
                strategy,
                rewrite == null || rewrite,
                includeTrace != null && includeTrace);
    }
}
