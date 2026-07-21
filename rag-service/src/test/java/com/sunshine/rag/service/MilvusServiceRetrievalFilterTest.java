package com.sunshine.rag.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MilvusServiceRetrievalFilterTest {

    @Test
    void retrievableChunkLevelExpr_excludesParents() {
        assertThat(MilvusService.retrievableChunkLevelExpr())
                .contains("child")
                .doesNotContain("parent");
    }
}
