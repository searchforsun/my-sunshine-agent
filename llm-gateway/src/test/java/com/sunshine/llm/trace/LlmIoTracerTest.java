package com.sunshine.llm.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.model.ChatCompletionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmIoTracerTest {

    private LlmIoTracer tracer;

    @BeforeEach
    void setUp() {
        tracer = new LlmIoTracer(new ObjectMapper(), true);
    }

    @Test
    void parseDelta_detectsReasoningContent() {
        String raw = """
                data: {"choices":[{"delta":{"reasoning_content":"先分析用户意图","content":""}}]}
                """;

        LlmIoTracer.DeltaFields fields = tracer.parseDelta(raw);

        assertThat(fields.hasReasoning()).isTrue();
        assertThat(fields.reasoning()).isEqualTo("先分析用户意图");
        assertThat(fields.hasContent()).isFalse();
    }

    @Test
    void parseDelta_detectsContentOnly() {
        String raw = "{\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}";

        LlmIoTracer.DeltaFields fields = tracer.parseDelta(raw);

        assertThat(fields.hasReasoning()).isFalse();
        assertThat(fields.hasContent()).isTrue();
        assertThat(fields.content()).isEqualTo("你好");
    }

    @Test
    void lastUserPreview_usesLatestUserMessage() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setMessages(java.util.List.of(
                msg("system", "你是助手"),
                msg("user", "第一条"),
                msg("assistant", "回复"),
                msg("user", "有哪些审批财务消息?")
        ));

        assertThat(LlmIoTracer.lastUserPreview(request)).isEqualTo("有哪些审批财务消息?");
    }

    private static ChatCompletionRequest.Message msg(String role, String content) {
        ChatCompletionRequest.Message message = new ChatCompletionRequest.Message();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
