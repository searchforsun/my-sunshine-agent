package com.sunshine.llm.usage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * token 用量收集（phase5 5.2.1）：非流式直接取 usage；流式从末尾 chunk 的根级 usage 提取，
 * 缺失时按 messages 字符数估算（estimated=true）。归一化（NormalizeFilter）保留根级 usage 字段，
 * 因此流式 chunk 的 usage 可直接解析。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenUsageCollector {

    private final ObjectMapper objectMapper;
    private final UsagePublisher publisher;
    private final LlmUsageProperties properties;

    /** 非流式：响应自带 usage 直接记录；缺失时估算。 */
    public void recordNonStream(ChatCompletionRequest request, ChatCompletionResponse response) {
        ChatCompletionResponse.Usage usage = response != null ? response.getUsage() : null;
        if (usage != null && usage.getTotalTokens() != null && usage.getTotalTokens() > 0) {
            int prompt = usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
            int completion = usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0;
            publish(build(request, request.getModel(), prompt, completion,
                    usage.getTotalTokens(), false, System.currentTimeMillis()));
            return;
        }
        int prompt = estimatePromptTokens(request);
        int completion = response != null ? estimateCompletionTokens(response) : 0;
        publish(build(request, request.getModel(), prompt, completion,
                prompt + completion, true, System.currentTimeMillis()));
    }

    public StreamUsageAccumulator newStreamAccumulator(ChatCompletionRequest request) {
        return new StreamUsageAccumulator(request);
    }

    /**
     * 流式累计器：逐 chunk 解析根级 usage 与 delta 字符数；complete 时若有 usage 直接记录，
     * 否则按 messages + 流式累计字符估算。
     */
    public class StreamUsageAccumulator {

        private final ChatCompletionRequest request;
        private LlmUsageRecord lastUsage;
        private int contentChars;
        private int reasoningChars;

        StreamUsageAccumulator(ChatCompletionRequest request) {
            this.request = request;
        }

        public void onChunk(String chunkJson) {
            if (chunkJson == null || chunkJson.isBlank()) {
                return;
            }
            String payload = chunkJson.strip();
            if (payload.startsWith("data:")) {
                payload = payload.substring(5).strip();
            }
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                return;
            }
            try {
                JsonNode root = objectMapper.readTree(payload);
                JsonNode usage = root.get("usage");
                if (usage != null && usage.isObject() && usage.hasNonNull("total_tokens")) {
                    int prompt = intValue(usage.get("prompt_tokens"));
                    int completion = intValue(usage.get("completion_tokens"));
                    int total = usage.get("total_tokens").asInt(0);
                    lastUsage = build(request, request.getModel(), prompt, completion, total, false,
                            System.currentTimeMillis());
                }
                JsonNode choices = root.get("choices");
                if (choices != null && choices.isArray() && !choices.isEmpty()) {
                    JsonNode delta = choices.get(0).get("delta");
                    if (delta != null && delta.isObject()) {
                        contentChars += delta.path("content").asText("").length();
                        reasoningChars += delta.path("reasoning_content").asText("").length();
                    }
                }
            } catch (Exception ignored) {
                // 非 JSON chunk（心跳/注释）跳过；usage 缺失时 complete 走估算
            }
        }

        public void complete() {
            if (lastUsage != null) {
                publisher.publish(lastUsage);
                return;
            }
            int prompt = estimatePromptTokens(request);
            int completion = Math.max(0, (contentChars + reasoningChars) / 2);
            publish(build(request, request.getModel(), prompt, completion,
                    prompt + completion, true, System.currentTimeMillis()));
        }
    }

    private void publish(LlmUsageRecord record) {
        publisher.publish(record);
    }

    private LlmUsageRecord build(
            ChatCompletionRequest request, String model, int prompt, int completion,
            int total, boolean estimated, long requestAt) {
        return new LlmUsageRecord(
                properties.getTenantId(),
                null,
                model,
                request.getCallSite(),
                null,
                null,
                Boolean.TRUE.equals(request.getStream()),
                prompt,
                completion,
                total,
                estimated,
                requestAt);
    }

    /** 粗估 prompt tokens：messages 字符总数 / 2（中英文混排近似）。 */
    static int estimatePromptTokens(ChatCompletionRequest request) {
        if (request == null || request.getMessages() == null) {
            return 0;
        }
        int chars = 0;
        for (ChatCompletionRequest.Message msg : request.getMessages()) {
            if (msg != null && msg.getContent() != null) {
                chars += String.valueOf(msg.getContent()).length();
            }
        }
        return Math.max(0, chars / 2);
    }

    /** 粗估非流式 completion tokens：正文 + 推理字符总数 / 2。 */
    static int estimateCompletionTokens(ChatCompletionResponse response) {
        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return 0;
        }
        ChatCompletionResponse.Message message = response.getChoices().get(0).getMessage();
        if (message == null) {
            return 0;
        }
        int chars = message.getContent() != null ? message.getContent().length() : 0;
        chars += message.getReasoningContent() != null ? message.getReasoningContent().length() : 0;
        return Math.max(0, chars / 2);
    }

    private static int intValue(JsonNode node) {
        return node != null ? node.asInt(0) : 0;
    }
}
