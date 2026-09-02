package com.sunshine.rag.chunker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FixedLengthChunkerTest {

    private final FixedLengthChunker chunker = new FixedLengthChunker();

    @Test
    void fixed_respectsMaxSizeAndOverlap() {
        String text = "甲".repeat(50) + "。乙".repeat(50) + "。";
        List<ChunkDraft> chunks = chunker.chunk(text,
                ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 40, "overlap", 10)));
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(0).text().length()).isLessThanOrEqualTo(40);
    }

    @Test
    void fixed_adjacentChunksShareOverlapOnHardCut() {
        int maxSize = 30;
        int overlap = 10;
        String text = "x".repeat(100);
        List<ChunkDraft> chunks = chunker.chunk(text,
                ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", maxSize, "overlap", overlap)));
        assertThat(chunks.size()).isGreaterThan(1);
        for (int i = 0; i < chunks.size(); i++) {
            assertThat(chunks.get(i).text().length()).isLessThanOrEqualTo(maxSize);
        }
        assertSharedOverlap(chunks.get(0).text(), chunks.get(1).text(), overlap);
    }

    @Test
    void fixed_prefersSentenceEndWithinWindow() {
        String prefix = "a".repeat(35);
        String text = prefix + "。后面还有" + "b".repeat(40);
        List<ChunkDraft> chunks = chunker.chunk(text,
                ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 40, "overlap", 0)));
        assertThat(chunks.get(0).text()).endsWith("。");
        assertThat(chunks.get(0).text().length()).isLessThan(40);
    }

    @Test
    void fixed_nullOrBlank_returnsEmpty() {
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 40, "overlap", 10));
        assertThat(chunker.chunk(null, params)).isEmpty();
        assertThat(chunker.chunk("   ", params)).isEmpty();
    }

    @Test
    void fixed_overlapAtLeastMaxSize_doesNotHang() {
        String text = "z".repeat(200);
        List<ChunkDraft> chunks = chunker.chunk(text,
                ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 20, "overlap", 20)));
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.size()).isLessThan(200);
    }

    static void assertSharedOverlap(String previous, String next, int overlap) {
        assertThat(previous.length()).isGreaterThanOrEqualTo(overlap);
        assertThat(next.length()).isGreaterThanOrEqualTo(overlap);
        assertThat(next.substring(0, overlap)).isEqualTo(previous.substring(previous.length() - overlap));
    }
}
