package com.sunshine.rag.admin.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sunshine.rag.config.RagChunkProperties;
import com.sunshine.rag.config.RagRerankProperties;
import com.sunshine.rag.config.RagSearchProperties;

/**
 * tenant Nacos 默认 + kb 稀疏覆盖后的有效检索参数。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EffectiveRagConfig(
        float minScore,
        String strategy,
        int rrfK,
        int hybridPoolSize,
        float rerankMinScore,
        int chunkMaxSize) {

    public static EffectiveRagConfig fromNacos(
            RagSearchProperties search,
            RagRerankProperties rerank,
            RagChunkProperties chunk) {
        return new EffectiveRagConfig(
                search.getMinScore(),
                search.getStrategy(),
                search.getRrfK(),
                search.getHybridPoolSize(),
                rerank.getMinScore(),
                chunk.getMaxSize());
    }

    public EffectiveRagConfig merge(EffectiveRagConfig override) {
        if (override == null) {
            return this;
        }
        return new EffectiveRagConfig(
                override.minScore > 0 ? override.minScore : minScore,
                override.strategy != null && !override.strategy.isBlank() ? override.strategy : strategy,
                override.rrfK > 0 ? override.rrfK : rrfK,
                override.hybridPoolSize > 0 ? override.hybridPoolSize : hybridPoolSize,
                override.rerankMinScore > 0 ? override.rerankMinScore : rerankMinScore,
                override.chunkMaxSize > 0 ? override.chunkMaxSize : chunkMaxSize);
    }
}
