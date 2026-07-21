package com.sunshine.rag.chunker;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecursiveChunkerTest {

    @Test
    void recursive_splitsOnBlankLinesFirst() {
        String text = "第一段内容足够长。\n\n第二段内容也足够长。\n\n第三段。";
        List<ChunkDraft> chunks = new RecursiveChunker()
                .chunk(text, ChunkParams.forStrategy(ChunkStrategy.RECURSIVE, Map.of("maxSize", 30, "overlap", 0)));
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
    }
}
