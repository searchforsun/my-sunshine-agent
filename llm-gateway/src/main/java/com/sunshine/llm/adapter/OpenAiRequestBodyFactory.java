package com.sunshine.llm.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.filter.NormalizeFilter;
import com.sunshine.llm.exception.ModelCapabilityException;
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
        if (stream && request != null && request.getStreamOptions() != null
                && !request.getStreamOptions().isEmpty()) {
            body.put("stream_options", request.getStreamOptions());
        } else {
            body.remove("stream_options");
        }
        body.remove("skip_cache");
        body.remove("fallback_model");
        body.remove("fallbackModel");
        body.remove("session_model");
        body.remove("sessionModel");
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
                throw new ModelCapabilityException(
                        NormalizeFilter.MODEL_NOT_TOOL_CALL, model);
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
     * 输出上限 SSOT = 注册表 {@code max_output_tokens}。
     * <p>调用方可能只写 {@code max_tokens}（AgentScope streaming）或 {@code max_completion_tokens}
     * （内部辅助补全），部分上游只认其中一键；两键同值下发，避免上游取到较小的一键而截断正文。
     * <p>调用方未声明时用注册表值兜底——禁止在代码里留隐式小默认，否则思考型模型会把预算全花在
     * reasoning 上、content 为空。
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
        Integer requested = minNumber(body.get("max_completion_tokens"), body.get("max_tokens"));
        if (requested != null && requested > cap) {
            log.info("[LLM-GW] clamp max output tokens {} → {} for model={}", requested, cap, model);
        }
        int budget = requested != null ? Math.min(requested, cap) : cap;
        body.put("max_completion_tokens", budget);
        body.put("max_tokens", budget);
    }

    /** 取两键中较小的已声明预算（未声明为 null） */
    private static Integer minNumber(Object primary, Object secondary) {
        Integer left = primary instanceof Number n ? n.intValue() : null;
        Integer right = secondary instanceof Number n ? n.intValue() : null;
        if (left == null) {
            return right;
        }
        return right == null ? left : Math.min(left, right);
    }
}
