package com.sunshine.llm.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * OpenAI 兼容 Chat Completion 响应
 */
@Data
@Builder
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
    public static class Choice {
        private Integer index;
        private Message message;
        @Builder.Default
        private String finishReason = "stop";

        @Data
        @Builder
        public static class Message {
            private String role;
            private String content;
        }
    }

    @Data
    @Builder
    public static class Usage {
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer totalTokens;
    }
}
