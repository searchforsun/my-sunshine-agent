package com.sunshine.llm.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.model.ChatCompletionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * 记录 LLM 网关入站请求与出站流式分片，便于确认上游是否返回 reasoning_content。
 */
@Slf4j
@Component
public class LlmIoTracer {

    private static final int PREVIEW_CHARS = 120;

    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public LlmIoTracer(
            ObjectMapper objectMapper,
            @Value("${llm.io-trace.enabled:true}") boolean enabled) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    public void logRequest(ChatCompletionRequest request) {
        if (!enabled || request == null) {
            return;
        }
        int messageCount = request.getMessages() != null ? request.getMessages().size() : 0;
        String lastUser = lastUserPreview(request);
        log.info("[LLM-IO] request model={} stream={} messages={} lastUser={}",
                request.getModel(), request.getStream(), messageCount, lastUser);
    }

    public Flux<ServerSentEvent<String>> traceStream(String model, Flux<ServerSentEvent<String>> upstream) {
        if (!enabled) {
            return upstream;
        }
        StreamAccumulator acc = new StreamAccumulator(model);
        return upstream
                .doOnNext(event -> acc.record(event.data()))
                .doOnComplete(acc::logSummary)
                .doOnError(e -> {
                    acc.logSummary();
                    log.warn("[LLM-IO] stream error model={}: {}", model, e.getMessage());
                });
    }

    static String lastUserPreview(ChatCompletionRequest request) {
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "-";
        }
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            ChatCompletionRequest.Message msg = request.getMessages().get(i);
            if (msg != null && "user".equals(msg.getRole())) {
                return preview(msg.getContent());
            }
        }
        ChatCompletionRequest.Message last = request.getMessages().get(request.getMessages().size() - 1);
        return preview(last != null ? last.getContent() : null);
    }

    static String preview(String text) {
        if (text == null || text.isBlank()) {
            return "-";
        }
        String normalized = text.strip().replace('\n', ' ');
        if (normalized.length() <= PREVIEW_CHARS) {
            return normalized;
        }
        return normalized.substring(0, PREVIEW_CHARS) + "...";
    }

    DeltaFields parseDelta(String raw) {
        if (raw == null || raw.isBlank() || "[DONE]".equals(raw.strip())) {
            return DeltaFields.EMPTY;
        }
        String json = raw.strip();
        if (json.startsWith("data:")) {
            json = json.substring(5).strip();
        }
        if (json.isEmpty() || "[DONE]".equals(json)) {
            return DeltaFields.EMPTY;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                return DeltaFields.EMPTY;
            }
            JsonNode choice = choices.get(0);
            JsonNode delta = choice.get("delta");
            if (delta != null && delta.isObject()) {
                return DeltaFields.from(delta);
            }
            JsonNode message = choice.get("message");
            if (message != null && message.isObject()) {
                return DeltaFields.from(message);
            }
        } catch (Exception ignored) {
            if (log.isDebugEnabled()) {
                log.debug("[LLM-IO] skip unparsable chunk: {}", preview(raw));
            }
        }
        return DeltaFields.EMPTY;
    }

    final class StreamAccumulator {
        private final String model;
        private int reasoningChunks;
        private int contentChunks;
        private int reasoningChars;
        private int contentChars;
        private String reasoningPreview = "";
        private String contentPreview = "";

        StreamAccumulator(String model) {
            this.model = model;
        }

        void record(String raw) {
            DeltaFields fields = parseDelta(raw);
            if (fields.hasReasoning()) {
                reasoningChunks++;
                reasoningChars += fields.reasoning.length();
                if (reasoningPreview.isEmpty()) {
                    reasoningPreview = preview(fields.reasoning);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[LLM-IO] chunk model={} channel=reasoning text={}",
                            model, preview(fields.reasoning));
                }
            }
            if (fields.hasContent()) {
                contentChunks++;
                contentChars += fields.content.length();
                if (contentPreview.isEmpty()) {
                    contentPreview = preview(fields.content);
                }
                if (log.isDebugEnabled()) {
                    log.debug("[LLM-IO] chunk model={} channel=content text={}",
                            model, preview(fields.content));
                }
            }
        }

        void logSummary() {
            boolean hasReasoning = reasoningChars > 0;
            log.info(
                    "[LLM-IO] stream done model={} reasoningChunks={} contentChunks={} "
                            + "reasoningChars={} contentChars={} hasReasoning={} "
                            + "reasoningPreview={} contentPreview={}",
                    model,
                    reasoningChunks,
                    contentChunks,
                    reasoningChars,
                    contentChars,
                    hasReasoning,
                    hasReasoning ? reasoningPreview : "-",
                    contentChars > 0 ? contentPreview : "-");
        }
    }

    record DeltaFields(String reasoning, String content) {
        static final DeltaFields EMPTY = new DeltaFields("", "");

        static DeltaFields from(JsonNode node) {
            String reasoning = firstNonBlank(
                    text(node, "reasoning_content"),
                    text(node, "reasoning"),
                    text(node, "thinking"));
            String content = text(node, "content");
            return new DeltaFields(reasoning, content);
        }

        boolean hasReasoning() {
            return reasoning != null && !reasoning.isEmpty();
        }

        boolean hasContent() {
            return content != null && !content.isEmpty();
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                return "";
            }
            return value.asText("");
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return "";
        }
    }
}
