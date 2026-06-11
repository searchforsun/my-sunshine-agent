package com.sunshine.bff.client;

import com.sunshine.bff.model.ChatRequest;
import com.sunshine.bff.model.UpdateTitleRequest;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

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

    public Flux<ServerSentEvent<String>> stream(ChatRequest request, String userId, String tenantId) {
        return webClient.post()
                .uri("/chat/stream")
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .doOnSubscribe(s -> log.info("[BFF] 连接 Orchestrator SSE"))
                .doOnError(e -> log.error("[BFF] Orchestrator 连接异常", e));
    }

    public Mono<List<Map<String, Object>>> listConversations(String userId, String tenantId) {
        return webClient.get()
                .uri("/conversations")
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    public Mono<Map<String, Object>> createConversation(String userId, String tenantId) {
        return webClient.post()
                .uri("/conversations")
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> getConversation(String id, String userId, String tenantId) {
        return webClient.get()
                .uri("/conversations/{id}", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> updateConversationTitle(
            String id, UpdateTitleRequest body, String userId, String tenantId) {
        return webClient.patch()
                .uri("/conversations/{id}", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Void> deleteConversation(String id, String userId, String tenantId) {
        return webClient.delete()
                .uri("/conversations/{id}", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .bodyToMono(Void.class);
    }

    public Flux<ServerSentEvent<String>> reconnectStream(
            String generationId, long afterSeq, String userId, String tenantId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/chat/stream/{generationId}")
                        .queryParam("afterSeq", afterSeq)
                        .build(generationId))
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .doOnSubscribe(s -> log.info("[BFF] 重连 generation SSE id={} afterSeq={}", generationId, afterSeq))
                .doOnError(e -> log.error("[BFF] generation 重连异常 id={}", generationId, e));
    }

    public Mono<Map<String, Object>> getGeneration(String id, String userId, String tenantId) {
        return webClient.get()
                .uri("/generations/{id}", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> cancelGeneration(String id, String userId, String tenantId) {
        return webClient.post()
                .uri("/generations/{id}/cancel", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}
