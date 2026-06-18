package com.sunshine.llm.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.model.ChatCompletionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiRequestBodyFactoryTest {

    private final OpenAiRequestBodyFactory factory = new OpenAiRequestBodyFactory(new ObjectMapper());

    @Test
    void build_preservesToolsAndToolCalls() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("deepseek-v4-pro");
        request.setTemperature(0.7);
        request.setMaxTokens(2048);

        ChatCompletionRequest.Message user = new ChatCompletionRequest.Message();
        user.setRole("user");
        user.setContent("查 pending 财务消息");

        ChatCompletionRequest.Message assistant = new ChatCompletionRequest.Message();
        assistant.setRole("assistant");
        assistant.setContent(null);
        ChatCompletionRequest.ToolCall call = new ChatCompletionRequest.ToolCall();
        call.setId("call_1");
        call.setType("function");
        ChatCompletionRequest.ToolCall.Function fn = new ChatCompletionRequest.ToolCall.Function();
        fn.setName("list_finance_messages");
        fn.setArguments("{\"status\":\"pending\"}");
        call.setFunction(fn);
        assistant.setToolCalls(List.of(call));

        request.setMessages(List.of(user, assistant));
        request.setTools(List.of(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "list_finance_messages",
                        "description", "查询财务消息",
                        "parameters", Map.of("type", "object")))));
        request.setToolChoice("auto");

        Map<String, Object> body = factory.build(request, true);

        assertThat(body.get("stream")).isEqualTo(true);
        assertThat(body.get("tools")).isNotNull();
        assertThat(body.get("tool_choice")).isEqualTo("auto");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) body.get("messages");
        assertThat(messages.get(1).get("tool_calls")).isNotNull();
    }
}
