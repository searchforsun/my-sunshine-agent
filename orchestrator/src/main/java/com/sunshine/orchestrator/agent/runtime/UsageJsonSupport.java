package com.sunshine.orchestrator.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.ChatUsage;

import java.util.LinkedHashMap;
import java.util.Map;

/** usage SSE 帧 / usage_json 落库的 JSON SSOT（spec §3.2） */
public final class UsageJsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        messageUsage.put("llmCalls", acc.llmCalls());
        map.put("messageUsage", messageUsage);
        if (groups != null && !groups.isEmpty()) {
            map.put("groups", groups);
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
            return new ReActAgentRuntime.UsageAccumulator(0, 0, 0);
        }
        try {
            JsonNode root = MAPPER.readTree(usageJson);
            JsonNode mu = root.has("messageUsage") ? root.get("messageUsage") : root;
            return new ReActAgentRuntime.UsageAccumulator(
                    mu.path("inputTokens").asLong(0),
                    mu.path("outputTokens").asLong(0),
                    mu.path("llmCalls").asInt(0));
        } catch (Exception e) {
            return new ReActAgentRuntime.UsageAccumulator(0, 0, 0);
        }
    }
}
