package com.sunshine.rag.service;

import com.sunshine.rag.config.RagSearchProperties;
import com.sunshine.rag.model.RetrievalCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HybridRetrievalServiceTest {

    private HybridRetrievalService service;

    @BeforeEach
    void setUp() {
        RagSearchProperties props = new RagSearchProperties();
        props.setRrfK(60);
        service = new HybridRetrievalService(props);
    }

    @Test
    void rrfPromotesDocPresentInBothLists() {
        RetrievalCandidate vectorHit = new RetrievalCandidate(
                "a#0", "报销制度", "餐费报销规则", 0.55f, RetrievalCandidate.SOURCE_VECTOR);
        RetrievalCandidate bm25Hit = new RetrievalCandidate(
                "a#0", "报销制度", "餐费报销规则", 12.3f, RetrievalCandidate.SOURCE_BM25);
        RetrievalCandidate vectorOnly = new RetrievalCandidate(
                "b#0", "差旅制度", "机票标准", 0.90f, RetrievalCandidate.SOURCE_VECTOR);

        List<RetrievalCandidate> fused = service.fuse(
                List.of(List.of(vectorHit, vectorOnly), List.of(bm25Hit)), 5);

        assertThat(fused).isNotEmpty();
        assertThat(fused.get(0).docName()).isEqualTo("报销制度");
        assertThat(fused.get(0).source()).isEqualTo(RetrievalCandidate.SOURCE_VECTOR);
    }

    @Test
    void assignDisplayScoresUsesVectorScoreFirst() {
        RetrievalCandidate vectorHit = new RetrievalCandidate(
                "a#0", "报销制度", "餐费报销规则", 0.72f, RetrievalCandidate.SOURCE_VECTOR);
        RetrievalCandidate bm25Only = new RetrievalCandidate(
                "b#0", "请假制度", "产假工资", 9.5f, RetrievalCandidate.SOURCE_BM25);
        List<RetrievalCandidate> ordered = List.of(vectorHit, bm25Only);
        List<RetrievalCandidate> scored = service.assignDisplayScores(
                ordered, List.of(vectorHit), List.of(bm25Only));
        assertThat(scored.get(0).score()).isEqualTo(0.72f);
        assertThat(scored.get(1).score()).isGreaterThanOrEqualTo(0.48f);
    }

    @Test
    void emptyListsReturnEmpty() {
        assertThat(service.fuse(List.of(List.of(), List.of()), 5)).isEmpty();
    }
}
