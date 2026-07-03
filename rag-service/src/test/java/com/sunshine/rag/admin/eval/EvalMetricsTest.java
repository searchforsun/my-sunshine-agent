package com.sunshine.rag.admin.eval;

import com.sunshine.rag.service.RetrievalService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EvalMetricsTest {

    @Test
    void recallAt5MatchesGoldenSetRule() {
        List<RetrievalService.DocFragment> hits = List.of(
                new RetrievalService.DocFragment("其他文档", "x", 0.9f),
                new RetrievalService.DocFragment("公司请假流程规范", "y", 0.8f));
        double recall = EvalMetrics.recallAtK(hits, Set.of("公司请假流程规范"), 5, 0.48f);
        assertThat(recall).isEqualTo(1.0);
    }

    @Test
    void mrrUsesFirstRelevantRank() {
        List<RetrievalService.DocFragment> hits = List.of(
                new RetrievalService.DocFragment("其他", "x", 0.9f),
                new RetrievalService.DocFragment("目标文档", "y", 0.8f));
        assertThat(EvalMetrics.mrr(hits, Set.of("目标文档"), 0.48f)).isEqualTo(0.5);
    }

    @Test
    void percentileInterpolatesP95() {
        List<Double> values = List.of(10.0, 20.0, 30.0, 40.0, 100.0);
        assertThat(EvalMetrics.percentile(values, 50)).isEqualTo(30.0);
        assertThat(EvalMetrics.percentile(values, 95)).isGreaterThan(40.0);
    }
}
