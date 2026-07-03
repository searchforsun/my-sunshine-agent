package com.sunshine.rag.admin.config;

import java.util.Map;

/** 将 draft payload 合并进 EffectiveRagConfig（smoke eval 用） */
public final class ConfigDraftMerger {

    private ConfigDraftMerger() {
    }

    public static EffectiveRagConfig merge(EffectiveRagConfig base, ConfigScope scope, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return base;
        }
        return switch (scope) {
            case RAG_SEARCH -> base.merge(new EffectiveRagConfig(
                    floatVal(payload, "minScore"),
                    stringVal(payload, "strategy"),
                    intVal(payload, "rrfK"),
                    intVal(payload, "hybridPoolSize"),
                    0,
                    0));
            case RAG_RERANK -> base.merge(new EffectiveRagConfig(0, null, 0, 0, floatVal(payload, "minScore"), 0));
            case RAG_CHUNK -> base.merge(new EffectiveRagConfig(0, null, 0, 0, 0, intVal(payload, "maxSize")));
            default -> base;
        };
    }

    private static float floatVal(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.floatValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Float.parseFloat(text.trim());
        }
        return 0;
    }

    private static int intVal(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text.trim());
        }
        return 0;
    }

    private static String stringVal(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value != null ? String.valueOf(value) : null;
    }
}
