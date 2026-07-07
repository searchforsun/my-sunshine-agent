package com.sunshine.rag.admin.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** 测试用完整配置 payload（结构须与 docker/mysql/init/14-sunshine-rag-service.sql 一致） */
public final class ConfigBundleTestFixtures {

    private ConfigBundleTestFixtures() {
    }

    public static Map<String, Object> fullPayload() {
        return fullPayload(0.48f, "hybrid+rerank");
    }

    public static Map<String, Object> fullPayload(float minScore, String strategy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("search", Map.of(
                "minScore", minScore,
                "strategy", strategy,
                "rrfK", 60,
                "hybridPoolSize", 20,
                "defaultTopK", 3));
        payload.put("rerank", Map.of(
                "enabled", true,
                "minScore", 0.25,
                "minRelevance", 0.25));
        payload.put("chunk", Map.of("maxSize", 1200));
        payload.put("rewrite", Map.of(
                "rag", Map.of(
                        "enabled", true,
                        "model", "deepseek-v4-flash",
                        "systemPrompt", "rag-prompt"),
                "hyde", Map.of(
                        "enabled", true,
                        "model", "deepseek-v4-flash",
                        "maxChars", 480,
                        "systemPrompt", "hyde-prompt"),
                "emptyRecall", Map.of(
                        "enabled", true,
                        "model", "deepseek-v4-flash",
                        "maxAlternatives", 2,
                        "systemPrompt", "empty-prompt")));
        return payload;
    }
}
