package com.sunshine.rag.chunker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FixedLengthChunkerTest {

    @Test
    void fixed_respectsMaxSizeAndOverlap() {
        String text = "甲".repeat(50) + "。乙".repeat(50) + "。";
        List<ChunkDraft> chunks = new FixedLengthChunker()
                .chunk(text, ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 40, "overlap", 10)));
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).text().length()).isLessThanOrEqualTo(40);
    }
}
