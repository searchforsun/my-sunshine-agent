package com.sunshine.llm.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.model.ChatCompletionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TokenUsageCollectorTest {

    @Mock
    private UsagePublisher publisher;

    private TokenUsageCollector collector;

    @BeforeEach
    void setUp() {
        LlmUsageProperties properties = new LlmUsageProperties();
        properties.setTenantId("default");
        collector = new TokenUsageCollector(new ObjectMapper(), publisher, properties);
    }

    @Test
    void recordNonStream_withUsage_recordsDirect() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("deepseek-v4-pro");
        request.setStream(false);
        request.setCallSite("rewrite");
        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .usage(ChatCompletionResponse.Usage.builder()
                        .promptTokens(120).completionTokens(45).totalTokens(165).build())
                .build();

        collector.recordNonStream(request, response);

        ArgumentCaptor<LlmUsageRecord> captor = ArgumentCaptor.forClass(LlmUsageRecord.class);
        verify(publisher).publish(captor.capture());
        LlmUsageRecord record = captor.getValue();
        assertThat(record.model()).isEqualTo("deepseek-v4-pro");
        assertThat(record.callSite()).isEqualTo("rewrite");
        assertThat(record.promptTokens()).isEqualTo(120);
        assertThat(record.completionTokens()).isEqualTo(45);
        assertThat(record.totalTokens()).isEqualTo(165);
        assertThat(record.estimated()).isFalse();
        assertThat(record.stream()).isFalse();
    }

    @Test
    void recordNonStream_withoutUsage_estimates() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("qwen-plus");
        ChatCompletionRequest.Message msg = new ChatCompletionRequest.Message();
        msg.setRole("user");
        msg.setContent("这是一段较长的用户提问内容，用于触发 token 估算");
        request.setMessages(List.of(msg));
        ChatCompletionResponse response = ChatCompletionResponse.builder()
                .choices(List.of(ChatCompletionResponse.Choice.builder()
                        .message(ChatCompletionResponse.Message.builder().content("模型回答正文").build())
                        .build()))
                .build();

        collector.recordNonStream(request, response);

        ArgumentCaptor<LlmUsageRecord> captor = ArgumentCaptor.forClass(LlmUsageRecord.class);
        verify(publisher).publish(captor.capture());
        LlmUsageRecord record = captor.getValue();
        assertThat(record.estimated()).isTrue();
        assertThat(record.promptTokens()).isGreaterThan(0);
        assertThat(record.completionTokens()).isGreaterThan(0);
        assertThat(record.totalTokens()).isEqualTo(record.promptTokens() + record.completionTokens());
    }

    @Test
    void stream_withUsageInLastChunk_recordsDirect() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("deepseek-v4-flash");
        request.setStream(true);
        request.setCallSite("chat");
        TokenUsageCollector.StreamUsageAccumulator acc = collector.newStreamAccumulator(request);

        acc.onChunk("{\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}");
        acc.onChunk("{\"choices\":[{\"delta\":{\"content\":\"世界\"}}],\"usage\":{\"prompt_tokens\":88,\"completion_tokens\":22,\"total_tokens\":110}}");
        acc.complete();

        ArgumentCaptor<LlmUsageRecord> captor = ArgumentCaptor.forClass(LlmUsageRecord.class);
        verify(publisher).publish(captor.capture());
        LlmUsageRecord record = captor.getValue();
        assertThat(record.stream()).isTrue();
        assertThat(record.callSite()).isEqualTo("chat");
        assertThat(record.promptTokens()).isEqualTo(88);
        assertThat(record.completionTokens()).isEqualTo(22);
        assertThat(record.totalTokens()).isEqualTo(110);
        assertThat(record.estimated()).isFalse();
    }

    @Test
    void stream_withoutUsage_estimatesFromMessagesAndChars() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("qwen-plus");
        request.setStream(true);
        ChatCompletionRequest.Message msg = new ChatCompletionRequest.Message();
        msg.setRole("user");
        msg.setContent("abcdef"); // 6 chars → prompt 估算 3
        request.setMessages(List.of(msg));
        TokenUsageCollector.StreamUsageAccumulator acc = collector.newStreamAccumulator(request);

        acc.onChunk("{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}"); // 5 chars
        acc.onChunk("{\"choices\":[{\"delta\":{\"reasoning_content\":\"think\"}}]}"); // 5 chars
        acc.complete();

        ArgumentCaptor<LlmUsageRecord> captor = ArgumentCaptor.forClass(LlmUsageRecord.class);
        verify(publisher).publish(captor.capture());
        LlmUsageRecord record = captor.getValue();
        assertThat(record.estimated()).isTrue();
        assertThat(record.promptTokens()).isEqualTo(3);
        // completion = (content 5 + reasoning 5) / 2 = 5
        assertThat(record.completionTokens()).isEqualTo(5);
    }

    @Test
    void stream_noChunkAtAll_publishesEstimated() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("qwen-plus");
        request.setStream(true);
        TokenUsageCollector.StreamUsageAccumulator acc = collector.newStreamAccumulator(request);

        acc.complete();

        ArgumentCaptor<LlmUsageRecord> captor = ArgumentCaptor.forClass(LlmUsageRecord.class);
        verify(publisher).publish(captor.capture());
        assertThat(captor.getValue().estimated()).isTrue();
    }

    @Test
    void streamDoneChunk_ignored() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("qwen-plus");
        request.setStream(true);
        TokenUsageCollector.StreamUsageAccumulator acc = collector.newStreamAccumulator(request);

        acc.onChunk("data: [DONE]");
        acc.onChunk(null);
        acc.complete();

        verify(publisher).publish(org.mockito.ArgumentMatchers.any(LlmUsageRecord.class));
    }
}
