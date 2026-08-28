package com.sunshine.llm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 Chat Completion 请求（含 tools / tool_calls，供 AgentScope ReAct 透传）
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatCompletionRequest {

    private String model;

    private List<Message> messages;

    private Double temperature = 0.7;

    @JsonProperty("max_tokens")
    private Integer maxTokens = 2048;

    private Boolean stream = false;

    /** Function Calling 工具定义 */
    private List<Object> tools;

    @JsonProperty("tool_choice")
    private Object toolChoice;

    /** 为 true 时 llm-gateway 语义缓存不读不写（Planner 等结构化输出） */
    @JsonProperty("skip_cache")
    private Boolean skipCache;

    /** 主模型失败时的降级模型（也可由请求头 X-Fallback-Model 注入） */
    @JsonProperty("fallback_model")
    private String fallbackModel;

    /** 流式 usage 透传（AgentScope 自动携带 include_usage）；非流式不转发 */
    @JsonProperty("stream_options")
    private Map<String, Object> streamOptions;

    /** 调用点标注（phase5 5.3）：chat|plan|worker|tool-call|rewrite|summarize|subagent；用量/路由维度 */
    @JsonProperty("call_site")
    private String callSite;

    /** 降级链切换模型时保留 tools / fallback 等字段 */
    public ChatCompletionRequest copyWithModel(String model) {
        ChatCompletionRequest copy = new ChatCompletionRequest();
        copy.setModel(model);
        copy.setMessages(this.messages);
        copy.setTemperature(this.temperature);
        copy.setMaxTokens(this.maxTokens);
        copy.setStream(this.stream);
        copy.setTools(this.tools);
        copy.setToolChoice(this.toolChoice);
        copy.setSkipCache(this.skipCache);
        copy.setFallbackModel(this.fallbackModel);
        copy.setStreamOptions(this.streamOptions);
        copy.setCallSite(this.callSite);
        return copy;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String role;
        /** 纯文本或多模态数组 */
        private Object content;
        @JsonProperty("tool_calls")
        private List<ToolCall> toolCalls;
        @JsonProperty("tool_call_id")
        private String toolCallId;
        private String name;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ToolCall {
        private String id;
        private String type;
        private Function function;

        @Data
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class Function {
            private String name;
            private String arguments;
        }
    }
}
