package com.sunshine.llm.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.filter.NormalizeFilter;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.registry.ModelCapabilities;
import com.sunshine.llm.registry.ModelDefinitionView;
import com.sunshine.llm.registry.ModelRegistryCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将 {@link ChatCompletionRequest} 转为上游 OpenAI 兼容 JSON：
 * 合并模型 {@code request_extras} → 按 capabilities 裁剪 → 钳制输出长度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiRequestBodyFactory {

    private final ObjectMapper objectMapper;
    private final ModelRegistryCache registryCache;

    public Map<String, Object> build(ChatCompletionRequest request, boolean stream) {
        return build(request, stream, null);
    }

    public Map<String, Object> build(
            ChatCompletionRequest request, boolean stream, ModelCapabilities capabilities) {
        Map<String, Object> body = objectMapper.convertValue(
                request, new TypeReference<LinkedHashMap<String, Object>>() {});
        body.put("stream", stream);
        body.remove("skip_cache");
        body.remove("fallback_model");
        body.remove("fallbackModel");
        String model = request != null ? request.getModel() : null;
        mergeRequestExtras(body, model);
        normalizeAndClampOutputTokens(body, model);
        if (capabilities != null) {
            if (!capabilities.isReasoning()) {
                body.remove("enable_thinking");
                body.remove("reasoning_effort");
                body.remove("reasoning_split");
                body.remove("thinking");
            }
            if (!capabilities.isToolCall() && request.getTools() != null && !request.getTools().isEmpty()) {
                throw new IllegalArgumentException(NormalizeFilter.MODEL_NOT_TOOL_CALL);
            }
        }
        return body;
    }

    /** 模型级 OpenAI 缺省参数：仅填补请求体中缺失/空的键，调用方显式字段优先 */
    private void mergeRequestExtras(Map<String, Object> body, String model) {
        if (model == null || model.isBlank() || registryCache == null) {
            return;
        }
        ModelDefinitionView def = registryCache.findDefinition(model).orElse(null);
        Map<String, Object> extras = def != null ? def.getRequestExtras() : null;
        if (extras == null || extras.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : extras.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
                continue;
            }
            // max_tokens 已废弃，不从 extras 注入
            if ("max_tokens".equals(entry.getKey())) {
                continue;
            }
            Object current = body.get(entry.getKey());
            if (current == null || (current instanceof String s && s.isBlank())) {
                body.put(entry.getKey(), entry.getValue());
            }
        }
    }

    /**
     * 输出上限 SSOT = max_completion_tokens（注册表 max_output_tokens）。
     * 兼容旧调用方仍传 max_tokens：钳制后同步到 max_completion_tokens。
     */
    private void normalizeAndClampOutputTokens(Map<String, Object> body, String model) {
        if (model == null || model.isBlank() || registryCache == null) {
            return;
        }
        ModelDefinitionView def = registryCache.findDefinition(model).orElse(null);
        if (def == null || def.getMaxOutputTokens() <= 0) {
            return;
        }
        int cap = def.getMaxOutputTokens();
        Object completion = body.get("max_completion_tokens");
        Object legacy = body.get("max_tokens");
        if (!(completion instanceof Number) && legacy instanceof Number) {
            body.put("max_completion_tokens", ((Number) legacy).intValue());
            completion = body.get("max_completion_tokens");
        }
        if (completion instanceof Number n) {
            int requested = n.intValue();
            if (requested > cap) {
                log.info("[LLM-GW] clamp max_completion_tokens {} → {} for model={}", requested, cap, model);
                body.put("max_completion_tokens", cap);
            }
        }
        if (legacy instanceof Number n) {
            int requested = n.intValue();
            if (requested > cap) {
                log.info("[LLM-GW] clamp max_tokens {} → {} for model={}", requested, cap, model);
                body.put("max_tokens", cap);
            }
        }
    }
}
