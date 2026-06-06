package com.sunshine.bff.client;

import com.sunshine.bff.model.ChatRequest;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * Orchestrator HTTP 客户端
 * 向 Orchestrator 的 /chat/stream 发送请求，透传 SSE 流
 */
@Slf4j
@Component
public class OrchestratorClient {

    @Value("${orchestrator.base-url:http://localhost:8200}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        log.info("[BFF] Orchestrator 客户端: baseUrl={}", baseUrl);
    }

    public Flux<ServerSentEvent<String>> stream(ChatRequest request, String userId,
                                                 String tenantId) {
        return webClient.post()
                .uri("/chat/stream")
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {
                })
                .doOnSubscribe(s -> log.info("[BFF] 连接 Orchestrator SSE"))
                .doOnError(e -> log.error("[BFF] Orchestrator 连接异常", e));
    }
}
