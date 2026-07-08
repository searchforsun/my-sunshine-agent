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
public class ExpertManagerClient {

    @Value("${expert-manager.base-url:http://localhost:8235}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
        log.info("[BFF] ExpertManager 客户端: baseUrl={}", baseUrl);
    }

    public Mono<Map<String, Object>> listExperts() {
        return get("/api/experts");
    }

    public Mono<Map<String, Object>> createExpert(Map<String, Object> body) {
        return webClient.post()
                .uri("/api/experts")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> updateExpert(String id, Map<String, Object> body) {
        return webClient.put()
                .uri("/api/experts/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> setEnabled(String id, boolean enabled) {
        return webClient.put()
                .uri("/api/experts/{id}/enable", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", enabled))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> deleteExpert(String id) {
        return webClient.delete()
                .uri("/api/experts/{id}", id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> catalogIndex() {
        return get("/api/experts/catalog/index");
    }

    public Mono<Map<String, Object>> catalogDetail(String id) {
        return get("/api/experts/" + id + "/catalog");
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
