package com.sunshine.llm.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.model.ChatCompletionRequest;
import com.sunshine.llm.registry.ModelCapabilities;
import com.sunshine.llm.registry.ModelDefinitionView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizeFilterTest {

    private final NormalizeFilter filter = new NormalizeFilter(new ObjectMapper());

    @Test
    void validate_multimodalFalse_withImage_throws() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        ChatCompletionRequest.Message msg = new ChatCompletionRequest.Message();
        msg.setRole("user");
        msg.setContent(List.of(Map.of("type", "image_url", "image_url", Map.of("url", "http://x"))));
        request.setMessages(List.of(msg));
        ModelDefinitionView def = ModelDefinitionView.builder()
                .modelName("qwen-plus")
                .capabilities(ModelCapabilities.builder().multimodal(false).toolCall(true).build())
                .build();
        assertThatThrownBy(() -> filter.validateRequest(request, def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(NormalizeFilter.MODEL_NOT_MULTIMODAL);
    }

    @Test
    void validate_toolCallFalse_withTools_throws() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setTools(List.of(Map.of("type", "function")));
        ModelDefinitionView def = ModelDefinitionView.builder()
                .modelName("x")
                .capabilities(ModelCapabilities.builder().toolCall(false).build())
                .build();
        assertThatThrownBy(() -> filter.validateRequest(request, def))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(NormalizeFilter.MODEL_NOT_TOOL_CALL);
    }

    @Test
    void normalize_reasoningTrue_renamesThinking() throws Exception {
        String out = filter.normalizeResponseBody(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\",\"thinking\":\"t1\"}}]}",
                true);
        assertThat(out).contains("reasoning_content");
        assertThat(out).doesNotContain("\"thinking\"");
    }

    @Test
    void normalize_reasoningFalse_stripsFields() {
        String out = filter.normalizeResponseBody(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\",\"reasoning_content\":\"r\"}}]}",
                false);
        assertThat(out).doesNotContain("reasoning_content");
    }

    @Test
    void normalizeStream_reasoningDetails_emitsIncrementalReasoningContent() throws Exception {
        NormalizeFilter.ReasoningStreamState state = new NormalizeFilter.ReasoningStreamState();
        String first = filter.normalizeStreamData(
                "{\"choices\":[{\"delta\":{\"reasoning_details\":[{\"text\":\"先分析\"}]}}]}",
                true,
                state);
        String second = filter.normalizeStreamData(
                "{\"choices\":[{\"delta\":{\"reasoning_details\":[{\"text\":\"先分析用户意图\"}]}}]}",
                true,
                state);

        var mapper = new ObjectMapper();
        assertThat(mapper.readTree(first)
                .path("choices").path(0).path("delta").path("reasoning_content").asText())
                .isEqualTo("先分析");
        assertThat(mapper.readTree(first).path("choices").path(0).path("delta").has("reasoning_details"))
                .isFalse();
        assertThat(mapper.readTree(second)
                .path("choices").path(0).path("delta").path("reasoning_content").asText())
                .isEqualTo("用户意图");
    }

    @Test
    void normalizeStream_thinkTagsInContent_promotedToReasoning() throws Exception {
        String raw = "{\"choices\":[{\"delta\":{\"content\":\"<think>内部规划</think>\\n对外回答\"}}]}";
        String out = filter.normalizeStreamData(raw, true);
        var root = new ObjectMapper().readTree(out).path("choices").path(0).path("delta");
        assertThat(root.path("reasoning_content").asText()).isEqualTo("内部规划");
        assertThat(root.path("content").asText()).isEqualTo("对外回答");
    }
}
