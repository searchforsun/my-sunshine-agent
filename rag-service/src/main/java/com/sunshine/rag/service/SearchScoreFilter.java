package com.sunshine.rag.service;

import java.util.List;

/** 按相似度阈值过滤 Milvus 检索结果 */
public final class SearchScoreFilter {

    private SearchScoreFilter() {
    }

    public static List<MilvusService.SearchHit> apply(List<MilvusService.SearchHit> hits, float minScore) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream()
                .filter(hit -> hit.score() >= minScore)
                .toList();
    }
}
