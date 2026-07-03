package com.sunshine.rag.admin.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测集 config_json 解析（扁平结构 SSOT）。
 * <pre>{@code
 * {
 *   "topK": [3, 5, 10],
 *   "minScore": 0.48,
 *   "gates": { "recallAt3Min": 0.95, ... }
 * }
 * }</pre>
 * 语料归属知识库 {@code document} 表；评测时按 kbId 解析 docId→displayName，不在此配置 corpus。
 */
@Component
@RequiredArgsConstructor
public class EvalSuiteConfigParser {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public Map<String, Object> defaultConfig() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("topK", List.of(3, 5, 10));
        root.put("minScore", 0.48);
        root.put("gates", Map.of());
        return root;
    }

    public Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return defaultConfig();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed != null ? parsed : defaultConfig();
        } catch (Exception e) {
            throw new IllegalStateException("config_json 解析失败: " + e.getMessage());
        }
    }

    public String write(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config != null ? config : defaultConfig());
        } catch (Exception e) {
            throw new IllegalStateException("config_json 序列化失败: " + e.getMessage());
        }
    }

    public GoldenSetLoader.EvalSettings toEvalSettings(Map<String, Object> config) {
        List<Integer> topK = new ArrayList<>();
        for (Object item : castList(config.get("topK"))) {
            if (item instanceof Number number) {
                topK.add(number.intValue());
            }
        }
        if (topK.isEmpty()) {
            topK = List.of(3, 5, 10);
        }
        float minScore = 0.48f;
        if (config.get("minScore") instanceof Number number) {
            minScore = number.floatValue();
        }
        Map<String, Object> gates = castMap(config.get("gates"));
        return new GoldenSetLoader.EvalSettings(topK, minScore, gates);
    }

    public List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    public String writeStringList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values != null ? values : List.of());
        } catch (Exception e) {
            return "[]";
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static List<?> castList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        return List.of();
    }

    private static String stringVal(Object value) {
        return value != null ? String.valueOf(value).trim() : "";
    }
}
