package com.sunshine.rag.chunker;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChunkParamsTest {

    @Test
    void parseStrategy_rejectsUnknown() {
        assertThatThrownBy(() -> ChunkStrategy.parse("foo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chunkParams_fixedRequiresPositiveMaxSize() {
        assertThatThrownBy(() -> ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void asMap_fixedWithZeroOverlapStillSerializesOverlap() {
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.FIXED, Map.of("maxSize", 800, "overlap", 0));
        assertThat(params.asMap()).containsEntry("overlap", 0);
    }
}
