package com.sunshine.llm.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.llm.model.ChatCompletionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiRequestBodyFactoryTest {

    private final OpenAiRequestBodyFactory factory =
            new OpenAiRequestBodyFactory(new ObjectMapper(), mockRegistry());

    private static com.sunshine.llm.registry.ModelRegistryCache mockRegistry() {
        com.sunshine.llm.registry.ModelRegistryCache cache =
                org.mockito.Mockito.mock(com.sunshine.llm.registry.ModelRegistryCache.class);
        org.mockito.Mockito.when(cache.findDefinition(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());
        return cache;
    }

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
        fn.setName("list_my_expenses");
        fn.setArguments("{\"status\":\"pending\"}");
        call.setFunction(fn);
        assistant.setToolCalls(List.of(call));

        request.setMessages(List.of(user, assistant));
        request.setTools(List.of(Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "list_my_expenses",
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

    @Test
    void build_reasoningFalse_stripsThinkingFields() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("qwen-plus");
        Map<String, Object> body = factory.build(request, false,
                com.sunshine.llm.registry.ModelCapabilities.builder().reasoning(false).toolCall(true).build());
        assertThat(body).doesNotContainKeys("enable_thinking", "reasoning_effort", "reasoning_split", "thinking");
        assertThat(body).doesNotContainKey("fallback_model");
    }

    @Test
    void build_mergesRequestExtrasForMissingKeys() {
        com.sunshine.llm.registry.ModelRegistryCache cache =
                org.mockito.Mockito.mock(com.sunshine.llm.registry.ModelRegistryCache.class);
        org.mockito.Mockito.when(cache.findDefinition("MiniMax-M3")).thenReturn(java.util.Optional.of(
                com.sunshine.llm.registry.ModelDefinitionView.builder()
                        .modelName("MiniMax-M3")
                        .maxOutputTokens(8192)
                        .requestExtras(Map.of("reasoning_split", true, "temperature", 0.3))
                        .build()));
        OpenAiRequestBodyFactory local = new OpenAiRequestBodyFactory(new ObjectMapper(), cache);
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("MiniMax-M3");
        request.setTemperature(0.9);
        Map<String, Object> body = local.build(request, true,
                com.sunshine.llm.registry.ModelCapabilities.builder().reasoning(true).toolCall(true).build());
        assertThat(body.get("reasoning_split")).isEqualTo(true);
        assertThat(body.get("temperature")).isEqualTo(0.9);
    }

    @Test
    void build_toolCallFalse_withTools_throws() {
        ChatCompletionRequest request = new ChatCompletionRequest();
        request.setModel("x");
        request.setTools(List.of(Map.of("type", "function")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                factory.build(request, false,
                        com.sunshine.llm.registry.ModelCapabilities.builder().toolCall(false).build()));
    }
}
