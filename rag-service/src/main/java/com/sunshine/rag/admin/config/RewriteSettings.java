package com.sunshine.rag.admin.config;

/** 单 kb 解析后的 rewrite 配置（来自 bundle payload） */
public record RewriteSettings(
        RewriteRagSettings rag,
        RewriteEmptyRecallSettings emptyRecall) {

    public record RewriteRagSettings(
            boolean enabled,
            String model,
            String systemPrompt,
            RewriteHydeSettings hyde) {
    }

    public record RewriteHydeSettings(
            boolean enabled,
            String model,
            int maxChars,
            String systemPrompt) {
    }

    public record RewriteEmptyRecallSettings(
            boolean enabled,
            String model,
            int maxAlternatives,
            String systemPrompt) {
    }
}
