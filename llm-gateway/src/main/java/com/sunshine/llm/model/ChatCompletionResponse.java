package com.sunshine.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * OpenAI 兼容 Chat Completion 响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionResponse {

    private String id;
    private String object;
    private Long created;
    private String model;
    private List<Choice> choices;
    private Usage usage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Choice {
        private Integer index;
        private Message message;
        @Builder.Default
        private String finishReason = "stop";
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String role;
        private String content;
        @com.fasterxml.jackson.annotation.JsonProperty("reasoning_content")
        private String reasoningContent;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage {
        @com.fasterxml.jackson.annotation.JsonProperty("prompt_tokens")
        private Integer promptTokens;
        @com.fasterxml.jackson.annotation.JsonProperty("completion_tokens")
        private Integer completionTokens;
        @com.fasterxml.jackson.annotation.JsonProperty("total_tokens")
        private Integer totalTokens;
    }
}
