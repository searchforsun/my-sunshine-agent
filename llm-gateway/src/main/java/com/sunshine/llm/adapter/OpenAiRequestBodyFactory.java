package com.sunshine.llm.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.model.ChatCompletionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将 {@link ChatCompletionRequest} 转为上游 OpenAI 兼容 JSON，保留 tools / tool_calls 等字段。
 */
@Component
@RequiredArgsConstructor
public class OpenAiRequestBodyFactory {

    private final ObjectMapper objectMapper;

    public Map<String, Object> build(ChatCompletionRequest request, boolean stream) {
        Map<String, Object> body = objectMapper.convertValue(
                request, new TypeReference<LinkedHashMap<String, Object>>() {});
        body.put("stream", stream);
        return body;
    }
}
