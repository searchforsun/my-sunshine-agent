package com.sunshine.rag.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkIdsTest {

    @Test
    void chunkId_roundTrip() {
        String id = ChunkIds.chunkId("doc-a", "20260701110011", 3);
        assertThat(id).isEqualTo("doc-a#v20260701110011#3");
        ChunkIds.Parsed parsed = ChunkIds.parse(id);
        assertThat(parsed).isNotNull();
        assertThat(parsed.docId()).isEqualTo("doc-a");
        assertThat(parsed.version()).isEqualTo("20260701110011");
        assertThat(parsed.index()).isEqualTo(3);
    }

    @Test
    void parentChunkId_usesParentIndex() {
        assertThat(ChunkIds.parentChunkId("doc-a", "20260701110011", 0))
                .isEqualTo("doc-a#v20260701110011#0");
    }
}
