package com.sunshine.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.codec.ServerSentEvent;

import java.util.Optional;

/**
 * 集成测试 SSE 解析 — 兼容 V2 JSON payload（content/reasoning/step）与 legacy 纯文本 chunk。
 */
public final class SseEventTestSupport {

    private SseEventTestSupport() {
    }

    public static boolean isContentChunk(ObjectMapper objectMapper, ServerSentEvent<String> ev) {
        String data = ev.data();
        if (data == null || data.isBlank()) {
            return false;
        }
        if (!data.startsWith("{")) {
            return true;
        }
        try {
            JsonNode node = objectMapper.readTree(data);
            return "content".equals(node.path("type").asText());
        } catch (Exception e) {
            return false;
        }
    }

    public static Optional<Long> contentSeq(ObjectMapper objectMapper, ServerSentEvent<String> ev) {
        String id = ev.id();
        if (id == null || !id.matches("\\d+")) {
            return Optional.empty();
        }
        if (!isContentChunk(objectMapper, ev)) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(id));
    }
}
