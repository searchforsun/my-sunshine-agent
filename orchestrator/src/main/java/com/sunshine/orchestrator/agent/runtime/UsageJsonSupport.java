package com.sunshine.orchestrator.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ChatUsage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** usage SSE 帧 / usage_json 落库的 JSON SSOT（spec §3.2） */
public final class UsageJsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 分组下发顺序 = 上下文实际位置（system 最前、messages 最后；对齐 Cursor Context Usage） */
    private static final List<String> GROUP_ORDER = List.of(
            "system", "tools", "rules", "skills", "contextLayers", "messages", "other");

    private UsageJsonSupport() {
    }

    public static String buildUsageWire(
            int callSeq, ChatUsage usage,
            ReActAgentRuntime.UsageAccumulator acc,
            Integer contextWindow, Map<String, Integer> groups) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "usage");
        map.put("callSeq", callSeq);
        map.put("inputTokens", usage.getInputTokens());
        map.put("outputTokens", usage.getOutputTokens());
        map.put("cachedTokens", usage.getCachedTokens());
        long contextTokens = usage.getInputTokens() + usage.getOutputTokens();
        map.put("contextTokens", contextTokens);
        if (contextWindow != null && contextWindow > 0) {
            map.put("contextWindowTokens", contextWindow);
            map.put("contextPercent", Math.round(100.0 * contextTokens / contextWindow));
        }
        Map<String, Object> messageUsage = new LinkedHashMap<>();
        messageUsage.put("inputTokens", acc.inputTokens());
        messageUsage.put("outputTokens", acc.outputTokens());
        messageUsage.put("cachedTokens", acc.cachedTokens());
        messageUsage.put("llmCalls", acc.llmCalls());
        map.put("messageUsage", messageUsage);
        // 缓存命中率 = Σcached / Σinput（消息级累计；input 含 cached，DeepSeek 同口径）
        if (acc.inputTokens() > 0) {
            map.put("cachedPercent", Math.round(100.0 * acc.cachedTokens() / acc.inputTokens()));
        }
        if (groups != null && !groups.isEmpty()) {
            // 入参 Map 可能无序（ConcurrentHashMap）：按上下文实际位置定序后下发
            Map<String, Integer> ordered = new LinkedHashMap<>();
            for (String key : GROUP_ORDER) {
                Integer v = groups.get(key);
                if (v != null) {
                    ordered.put(key, v);
                }
            }
            for (Map.Entry<String, Integer> e : groups.entrySet()) {
                ordered.putIfAbsent(e.getKey(), e.getValue());
            }
            map.put("groups", ordered);
        }
        try {
            return MAPPER.writeValueAsString(map);
        } catch (Exception e) {
            return "{\"type\":\"usage\",\"callSeq\":" + callSeq + "}";
        }
    }

    /** 续跑起算：从落库 usage_json 的 messageUsage 恢复累计 */
    public static ReActAgentRuntime.UsageAccumulator parseAccumulator(String usageJson) {
        if (usageJson == null || usageJson.isBlank()) {
            return new ReActAgentRuntime.UsageAccumulator(0, 0, 0, 0);
        }
        try {
            JsonNode root = MAPPER.readTree(usageJson);
            JsonNode mu = root.has("messageUsage") ? root.get("messageUsage") : root;
            return new ReActAgentRuntime.UsageAccumulator(
                    mu.path("inputTokens").asLong(0),
                    mu.path("outputTokens").asLong(0),
                    mu.path("cachedTokens").asLong(0),
                    mu.path("llmCalls").asInt(0));
        } catch (Exception e) {
            return new ReActAgentRuntime.UsageAccumulator(0, 0, 0, 0);
        }
    }
}
