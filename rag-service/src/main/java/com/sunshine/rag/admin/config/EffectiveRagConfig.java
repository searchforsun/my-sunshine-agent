package com.sunshine.rag.admin.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 单 kb 已发布配置中的检索参数视图 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EffectiveRagConfig(
        float minScore,
        String strategy,
        int rrfK,
        int hybridPoolSize,
        float rerankMinScore,
        int chunkMaxSize) {

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
