package com.sunshine.rag.model;

import java.util.Locale;

/**
 * 检索策略：请求体 strategy 优先于 Nacos 默认。
 */
public enum SearchStrategy {
    VECTOR,
    HYBRID,
    HYBRID_RERANK;

    public static SearchStrategy from(String raw, SearchStrategy defaultStrategy) {
        if (raw == null || raw.isBlank()) {
            return defaultStrategy;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "vector" -> VECTOR;
            case "hybrid" -> HYBRID;
            case "hybrid+rerank", "hybrid_rerank" -> HYBRID_RERANK;
            default -> defaultStrategy;
        };
    }
}
