package com.sunshine.rag.admin.config;

/** 单 kb 运行时有效配置（检索 + 改写） */
public record ResolvedKbConfig(
        EffectiveRagConfig retrieval,
        RewriteSettings rewrite,
        int defaultTopK) {
}
