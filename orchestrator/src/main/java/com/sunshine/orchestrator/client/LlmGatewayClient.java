package com.sunshine.orchestrator.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.conversation.ChatTurn;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM Gateway 直连客户端 — 逐 token 流式，不经过 AgentScope
 */
@Slf4j
@Component
public class LlmGatewayClient {

    @Value("${agent.model.base-url:http://127.0.0.1:8300/v1}")
    private String baseUrl;

    @Value("${agent.model.api-key:}")
    private String apiKey;

    @Value("${agent.model.name:deepseek-v4-pro}")
    private String modelName;

    @Value("${agent.system-prompt:你是一个智能助手，优先检索知识库回答用户问题。}")
    private String systemPrompt;

    private WebClient webClient;
    private final ObjectMapper om = new ObjectMapper();

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        log.info("[LlmGatewayClient] baseUrl={}", baseUrl);
    }

    public Flux<String> streamDirectly(String userMessage) {
        return streamWithHistory(List.of(), userMessage);
    }

    public Flux<String> streamWithHistory(List<ChatTurn> history, String userMessage) {
        List<Map<String, Object>> messages = buildMessages(history, userMessage, null);
        return doStream(messages);
    }

    public Flux<String> streamContinue(List<ChatTurn> history, String userMessage, String partialAssistant) {
        List<Map<String, Object>> messages = buildMessages(history, userMessage, partialAssistant);
        return doStream(messages);
    }

    private List<Map<String, Object>> buildMessages(
            List<ChatTurn> history, String userMessage, String partialAssistant) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null) {
            for (ChatTurn turn : history) {
                if ("user".equals(turn.role()) || "assistant".equals(turn.role())) {
                    messages.add(Map.of("role", turn.role(), "content", turn.content()));
                }
            }
        }
        messages.add(Map.of("role", "user", "content", userMessage));
        if (partialAssistant != null && !partialAssistant.isEmpty()) {
            messages.add(Map.of("role", "assistant", "content", partialAssistant));
        }
        return messages;
    }

    private Flux<String> doStream(List<Map<String, Object>> messages) {
        Map<String, Object> request = Map.of(
                "model", modelName,
                "messages", messages,
                "stream", true
        );

        return webClient.post()
                .uri("/chat/completions/stream")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .flatMap(this::extractDelta)
                .doOnSubscribe(s -> log.info("[LlmGatewayClient] 直连流式开始"))
                .doOnError(e -> log.error("[LlmGatewayClient] 流式异常: {}", e.getMessage()));
    }

    @SuppressWarnings("unchecked")
    private Flux<String> extractDelta(String chunk) {
        String json = chunk.trim();
        if (json.isEmpty()) {
            return Flux.empty();
        }

        try {
            Map<String, Object> root = om.readValue(json, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
            if (choices == null || choices.isEmpty()) {
                return Flux.empty();
            }
            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
            if (delta == null) {
                return Flux.empty();
            }
            Object content = delta.get("content");
            if (content instanceof String s && !s.isEmpty()) {
                return Flux.just(s);
            }
        } catch (Exception ignored) {
        }
        return Flux.empty();
    }
}
