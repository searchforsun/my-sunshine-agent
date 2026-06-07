package com.sunshine.orchestrator.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

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

    /**
     * 直连 LLM Gateway 流式端点，解析 OpenAI delta 格式，逐 token 返回纯文本
     */
    public Flux<String> streamDirectly(String userMessage) {
        Map<String, Object> request = Map.of(
                "model", "deepseek-v4-pro",
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "你是一个智能助手，优先检索知识库回答用户问题。回答简洁准确。"),
                        Map.of("role", "user", "content", userMessage)
                ),
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

    /**
     * 从 LLM Gateway SSE 流中提取 delta.content
     * 兼容单行 data 和多行 SSE 帧格式
     */
    @SuppressWarnings("unchecked")
    private reactor.core.publisher.Flux<String> extractDelta(String chunk) {
        String json = chunk.trim();
        if (json.isEmpty()) return reactor.core.publisher.Flux.empty();

        try {
            Map<String, Object> root = om.readValue(json, Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) root.get("choices");
            if (choices == null || choices.isEmpty()) return reactor.core.publisher.Flux.empty();
            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
            if (delta == null) return reactor.core.publisher.Flux.empty();
            Object content = delta.get("content");
            if (content instanceof String s && !s.isEmpty()) {
                return reactor.core.publisher.Flux.just(s);
            }
        } catch (Exception ignored) {
        }
        return reactor.core.publisher.Flux.empty();
    }
}
