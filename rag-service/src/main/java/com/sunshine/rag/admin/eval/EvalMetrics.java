package com.sunshine.rag.admin.eval;

import com.sunshine.rag.service.RetrievalService;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** RAG 评测指标（对齐 scripts/rag_eval.py） */
public final class EvalMetrics {

    private EvalMetrics() {
    }

    public static double recallAtK(
            List<RetrievalService.DocFragment> hits, Set<String> relevantNames, int k, float minScore) {
        if (relevantNames.isEmpty()) {
            return 0.0;
        }
        int limit = Math.min(k, hits.size());
        for (int i = 0; i < limit; i++) {
            RetrievalService.DocFragment hit = hits.get(i);
            if (hit.score() < minScore) {
                continue;
            }
            if (relevantNames.contains(hit.docName())) {
                return 1.0;
            }
        }
        return 0.0;
    }

    public static double mrr(List<RetrievalService.DocFragment> hits, Set<String> relevantNames, float minScore) {
        if (relevantNames.isEmpty()) {
            return 0.0;
        }
        for (int i = 0; i < hits.size(); i++) {
            RetrievalService.DocFragment hit = hits.get(i);
            if (hit.score() < minScore) {
                continue;
            }
            if (relevantNames.contains(hit.docName())) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    public static Integer firstRelevantRank(
            List<RetrievalService.DocFragment> hits, Set<String> relevantNames, float minScore) {
        for (int i = 0; i < hits.size(); i++) {
            RetrievalService.DocFragment hit = hits.get(i);
            if (hit.score() < minScore) {
                continue;
            }
            if (relevantNames.contains(hit.docName())) {
                return i + 1;
            }
        }
        return null;
    }

    public static List<RetrievalService.DocFragment> filterByMinScore(
            List<RetrievalService.DocFragment> hits, float minScore) {
        return hits.stream().filter(hit -> hit.score() >= minScore).toList();
    }

    public static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return 0.0;
        }
        List<Double> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        if (sorted.size() == 1) {
            return sorted.getFirst();
        }
        double rank = (p / 100.0) * (sorted.size() - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        if (lower == upper) {
            return sorted.get(lower);
        }
        double weight = rank - lower;
        return sorted.get(lower) * (1 - weight) + sorted.get(upper) * weight;
    }
}
