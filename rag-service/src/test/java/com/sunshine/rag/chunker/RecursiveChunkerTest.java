package com.sunshine.rag.chunker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.sunshine.rag.chunker.FixedLengthChunkerTest.assertSharedOverlap;
import static org.assertj.core.api.Assertions.assertThat;

class RecursiveChunkerTest {

    private final RecursiveChunker chunker = new RecursiveChunker();

    @Test
    void recursive_splitsOnBlankLinesFirst() {
        String text = "第一段内容足够长。\n\n第二段内容也足够长。\n\n第三段。";
        List<ChunkDraft> chunks = chunker.chunk(text,
                ChunkParams.forStrategy(ChunkStrategy.RECURSIVE, Map.of("maxSize", 30, "overlap", 0)));
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void recursive_blankLineSplit_appliesOverlapCutback() {
        int overlap = 5;
        String text = "ABCDEFGHIJ\n\nKLMNOPQRST";
        List<ChunkDraft> chunks = chunker.chunk(text,
                ChunkParams.forStrategy(ChunkStrategy.RECURSIVE, Map.of("maxSize", 30, "overlap", overlap)));
        assertThat(chunks).hasSize(2);
        assertSharedOverlap(chunks.get(0).text(), chunks.get(1).text(), overlap);
    }

    @Test
    void recursive_nullOrBlank_returnsEmpty() {
        ChunkParams params = ChunkParams.forStrategy(ChunkStrategy.RECURSIVE, Map.of("maxSize", 30, "overlap", 5));
        assertThat(chunker.chunk(null, params)).isEmpty();
        assertThat(chunker.chunk("\n\t", params)).isEmpty();
    }

    @Test
    void recursive_overlapAtLeastMaxSize_doesNotHang() {
        String text = "p".repeat(200);
        List<ChunkDraft> chunks = chunker.chunk(text,
                ChunkParams.forStrategy(ChunkStrategy.RECURSIVE, Map.of("maxSize", 20, "overlap", 25)));
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.size()).isLessThan(200);
    }
}
