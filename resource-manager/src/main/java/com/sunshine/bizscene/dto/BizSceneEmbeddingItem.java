package com.sunshine.bizscene.dto;

/**
 * 场景 embedding 检索索引项（authority §2.1b/§2.1c）：供 orchestrator 场景 embedding 服务缓存/余弦匹配。
 * {@code descriptionVector} 为 JSON float[] 字符串（空/null 表示待回填）。
 */
public record BizSceneEmbeddingItem(
        String bizScene,
        String displayName,
        String description,
        String descriptionVector,
        String status,
        String source,
        String tenantId
) {
}
