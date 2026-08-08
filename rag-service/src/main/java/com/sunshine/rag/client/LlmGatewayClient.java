package com.sunshine.rag.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.rag.config.RagLlmProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** llm-gateway 非流式补全 — Query 改写专用 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmGatewayClient {
    private final RagLlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public LlmGatewayClient(RagLlmProperties llmProperties, ObjectMapper objectMapper, WebClient.Builder builder) {
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.webClient = builder
                .baseUrl("http://sunshine-llm-gateway/v1")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        log.info("[RagLlmGatewayClient] baseUrl=http://sunshine-llm-gateway/v1");
    }

    public String complete(String model, String systemPrompt, String userContent) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt.strip()));
        }
        messages.add(Map.of("role", "user", "content", userContent != null ? userContent : ""));
        String resolvedModel = model != null && !model.isBlank() ? model : llmProperties.getDefaultModel();
        Map<String, Object> request = Map.of(
                "model", resolvedModel,
                "messages", messages,
                "stream", false);
        try {
            Map<String, Object> response = webClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + llmProperties.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
            if (response == null) {
                return "";
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                return "";
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null || message.get("content") == null) {
                return "";
            }
            return message.get("content").toString();
        } catch (Exception e) {
            log.warn("[RagLlmGatewayClient] complete 失败: {}", e.getMessage());
            return "";
        }
    }
}
