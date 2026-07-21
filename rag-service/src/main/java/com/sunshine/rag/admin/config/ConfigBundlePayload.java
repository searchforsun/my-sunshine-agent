package com.sunshine.rag.admin.config;

import com.sunshine.rag.exception.RagErrorCode;

import java.util.Map;

/** 整包 payload → 运行时 ResolvedKbConfig（仅 DB payload，缺失即报错） */
public final class ConfigBundlePayload {

    private ConfigBundlePayload() {
    }

    public static ResolvedKbConfig toResolvedKbConfig(Map<String, Object> payload) {
        requirePayload(payload);
        EffectiveRagConfig retrieval = requireRetrieval(payload);
        Map<String, Object> search = requireMap(payload, "search");
        return new ResolvedKbConfig(
                retrieval,
                parseRewrite(payload),
                requireInt(search, "defaultTopK"));
    }

    public static EffectiveRagConfig requireRetrieval(Map<String, Object> payload) {
        requirePayload(payload);
        Map<String, Object> search = requireMap(payload, "search");
        Map<String, Object> rerank = requireMap(payload, "rerank");
        return new EffectiveRagConfig(
                requireFloat(search, "minScore"),
                requireString(search, "strategy"),
                requireInt(search, "rrfK"),
                requireInt(search, "hybridPoolSize"),
                requireFloat(rerank, "minScore"));
    }

    @SuppressWarnings("unchecked")
    public static RewriteSettings parseRewrite(Map<String, Object> payload) {
        requirePayload(payload);
        Map<String, Object> rewrite = requireMap(payload, "rewrite");
        Map<String, Object> rag = requireMap(rewrite, "rag");
        if (rag.containsKey("hyde")) {
            throw invalid("rewrite.rag 不得嵌套 hyde，请使用 rewrite.hyde");
        }
        Map<String, Object> hyde = requireMap(rewrite, "hyde");
        Map<String, Object> emptyRecall = requireMap(rewrite, "emptyRecall");
        return new RewriteSettings(
                new RewriteSettings.RewriteRagSettings(
                        requireBool(rag, "enabled"),
                        requireString(rag, "model"),
                        requireString(rag, "systemPrompt"),
                        parseHyde(hyde)),
                parseEmptyRecall(emptyRecall));
    }

    public static Object pathValue(Map<String, Object> payload, String... path) {
        requirePayload(payload);
        Object current = payload;
        for (String segment : path) {
            if (!(current instanceof Map<?, ?> map)) {
                throw invalid("配置路径不存在: " + String.join(".", path));
            }
            current = ((Map<String, Object>) current).get(segment);
            if (current == null) {
                throw invalid("配置路径不存在: " + String.join(".", path));
            }
        }
        return current;
    }

    private static RewriteSettings.RewriteHydeSettings parseHyde(Map<String, Object> hyde) {
        return new RewriteSettings.RewriteHydeSettings(
                requireBool(hyde, "enabled"),
                requireString(hyde, "model"),
                requireInt(hyde, "maxChars"),
                requireString(hyde, "systemPrompt"));
    }

    private static RewriteSettings.RewriteEmptyRecallSettings parseEmptyRecall(Map<String, Object> emptyRecall) {
        return new RewriteSettings.RewriteEmptyRecallSettings(
                requireBool(emptyRecall, "enabled"),
                requireString(emptyRecall, "model"),
                requireInt(emptyRecall, "maxAlternatives"),
                requireString(emptyRecall, "systemPrompt"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireMap(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid("缺少配置段: " + key);
        }
        return (Map<String, Object>) map;
    }

    private static void requirePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            throw invalid("配置 payload 为空");
        }
    }

    private static float requireFloat(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.floatValue();
        }
        throw invalid("缺少或类型错误: " + key);
    }

    private static int requireInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            int n = number.intValue();
            if (n <= 0) {
                throw invalid("无效数值: " + key);
            }
            return n;
        }
        throw invalid("缺少或类型错误: " + key);
    }

    private static boolean requireBool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        throw invalid("缺少或类型错误: " + key);
    }

    private static String requireString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw invalid("缺少配置项: " + key);
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            throw invalid("配置项为空: " + key);
        }
        return text;
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException(RagErrorCode.CONFIG_PAYLOAD_INVALID.getMessage() + ": " + detail);
    }
}
