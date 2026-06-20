package com.sunshine.rag.model;

/**
 * 向量 / BM25 / RRF / Rerank 统一候选片段。
 */
public record RetrievalCandidate(
        String chunkId,
        String docName,
        String content,
        float score,
        String source
) {
    public static final String SOURCE_VECTOR = "vector";
    public static final String SOURCE_BM25 = "bm25";
    public static final String SOURCE_RRF = "rrf";
    public static final String SOURCE_RERANK = "rerank";

    public String dedupeKey() {
        if (chunkId != null && !chunkId.isBlank()) {
            return chunkId;
        }
        return docName + "\0" + content;
    }

    public RetrievalCandidate withScore(float newScore) {
        return new RetrievalCandidate(chunkId, docName, content, newScore, source);
    }

    public RetrievalCandidate withSource(String newSource) {
        return new RetrievalCandidate(chunkId, docName, content, score, newSource);
    }
}
