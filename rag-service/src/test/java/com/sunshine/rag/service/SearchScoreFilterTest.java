package com.sunshine.rag.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchScoreFilterTest {

    @Test
    void filtersBelowMinScore() {
        List<MilvusService.SearchHit> hits = List.of(
                new MilvusService.SearchHit("A", "相关", 0.72f),
                new MilvusService.SearchHit("B", "弱相关", 0.41f),
                new MilvusService.SearchHit("C", "边界", 0.48f));

        List<MilvusService.SearchHit> filtered = SearchScoreFilter.apply(hits, 0.48f);

        assertThat(filtered).hasSize(2);
        assertThat(filtered.get(0).docName()).isEqualTo("A");
        assertThat(filtered.get(1).docName()).isEqualTo("C");
    }

    @Test
    void emptyWhenAllBelowThreshold() {
        List<MilvusService.SearchHit> hits = List.of(
                new MilvusService.SearchHit("B", "弱相关", 0.35f));

        assertThat(SearchScoreFilter.apply(hits, 0.48f)).isEmpty();
    }
}
