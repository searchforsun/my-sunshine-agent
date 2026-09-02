package com.sunshine.rag.chunker;

import java.util.Locale;

/** 文档分块策略 id，与 API / document_version.chunk_strategy 一致 */
public enum ChunkStrategy {
    MARKDOWN("markdown"),
    FIXED("fixed"),
    RECURSIVE("recursive"),
    SEMANTIC("semantic"),
    PARENT_CHILD("parent_child");

    private final String wire;

    ChunkStrategy(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static ChunkStrategy parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Unknown chunk strategy: " + raw);
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (ChunkStrategy strategy : values()) {
            if (strategy.wire.equals(normalized)) {
                return strategy;
            }
        }
        throw new IllegalArgumentException("Unknown chunk strategy: " + raw);
    }
}
