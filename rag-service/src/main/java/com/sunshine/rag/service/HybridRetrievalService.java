package com.sunshine.rag.service;

import com.sunshine.rag.config.RagSearchProperties;
import com.sunshine.rag.model.RetrievalCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 向量 + BM25 候选 RRF 融合。
 */
@Service
@RequiredArgsConstructor
public class HybridRetrievalService {

    private final RagSearchProperties searchProperties;

    public List<RetrievalCandidate> fuse(List<List<RetrievalCandidate>> rankedLists, int limit) {
        int rrfK = searchProperties.getRrfK();
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, RetrievalCandidate> candidates = new LinkedHashMap<>();
        for (List<RetrievalCandidate> list : rankedLists) {
            for (int rank = 0; rank < list.size(); rank++) {
                RetrievalCandidate candidate = list.get(rank);
                String key = candidate.dedupeKey();
                scores.merge(key, 1.0 / (rrfK + rank + 1), Double::sum);
                candidates.putIfAbsent(key, candidate);
            }
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<RetrievalCandidate> fused = new ArrayList<>();
        for (int i = 0; i < sorted.size() && fused.size() < limit; i++) {
            fused.add(candidates.get(sorted.get(i).getKey()));
        }
        return fused;
    }

    /**
     * RRF 仅决定排序；对外 score 取向量分，BM25 独命中时按相对 BM25 映射到 [0.48,1]。
     */
    public List<RetrievalCandidate> assignDisplayScores(
            List<RetrievalCandidate> ordered,
            List<RetrievalCandidate> vectorHits,
            List<RetrievalCandidate> bm25Hits) {
        Map<String, Float> vectorScores = scoreMap(vectorHits);
        Map<String, Float> bm25Scores = scoreMap(bm25Hits);
        float maxBm25 = bm25Scores.values().stream().max(Float::compare).orElse(0f);
        List<RetrievalCandidate> scored = new ArrayList<>();
        for (RetrievalCandidate candidate : ordered) {
            String key = candidate.dedupeKey();
            float vector = vectorScores.getOrDefault(key, 0f);
            float bm25 = bm25Scores.getOrDefault(key, 0f);
            float display;
            if (vector > 0f) {
                display = vector;
            } else if (bm25 > 0f && maxBm25 > 0f) {
                display = 0.48f + 0.52f * (bm25 / maxBm25);
            } else {
                display = 0f;
            }
            scored.add(candidate.withScore(display).withSource(RetrievalCandidate.SOURCE_RRF));
        }
        return scored;
    }

    private static Map<String, Float> scoreMap(List<RetrievalCandidate> hits) {
        Map<String, Float> map = new LinkedHashMap<>();
        for (RetrievalCandidate hit : hits) {
            map.merge(hit.dedupeKey(), hit.score(), Math::max);
        }
        return map;
    }
}
