package com.sunshine.bff.client;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import com.sunshine.common.web.RemoteErrorMapper;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Component
public class AgentManagerClient {

    @Value("${resource-manager.base-url:http://localhost:8240}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
        log.info("[BFF] AgentManager 客户端: baseUrl={}", baseUrl);
    }

    public Mono<Map<String, Object>> listAgents() {
        return get("/api/agents");
    }

    public Mono<Map<String, Object>> createAgent(Map<String, Object> body) {
        return webClient.post()
                .uri("/api/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> updateAgent(String id, Map<String, Object> body) {
        return webClient.put()
                .uri("/api/agents/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> setEnabled(String id, boolean enabled) {
        return webClient.put()
                .uri("/api/agents/{id}/enable", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", enabled))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> deleteAgent(String id) {
        return webClient.delete()
                .uri("/api/agents/{id}", id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> catalogIndex(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return get("/api/agents/catalog/index");
        }
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/agents/catalog/index")
                        .queryParam("tenantId", tenantId)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> catalogDetail(String id) {
        return get("/api/agents/" + id + "/catalog");
    }

    public Mono<Map<String, Object>> fetchAgentCard(String agentCardUrl) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/agents/external/card-prefill")
                        .queryParam("agentCardUrl", agentCardUrl)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private Mono<Map<String, Object>> get(String path) {
        return webClient.get()
                .uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private Mono<? extends Throwable> toBizError(ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> Mono.error(RemoteErrorMapper.fromBody(response.statusCode().value(), body)));
    }
}
