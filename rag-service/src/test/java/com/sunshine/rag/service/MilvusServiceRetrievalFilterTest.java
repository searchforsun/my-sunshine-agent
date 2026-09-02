package com.sunshine.rag.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MilvusServiceRetrievalFilterTest {

    @Test
    void retrievableChunkLevelExpr_excludesParents() {
        assertThat(MilvusService.retrievableChunkLevelExpr())
                .isEqualTo("chunk_level == \"chunk\" || chunk_level == \"child\"")
                .doesNotContain("chunk_level == \"\"")
                .contains("chunk_level == \"chunk\"")
                .contains("chunk_level == \"child\"")
                .doesNotContain("parent");
    }
}
