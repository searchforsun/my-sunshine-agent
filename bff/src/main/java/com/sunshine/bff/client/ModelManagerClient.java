package com.sunshine.bff.client;

import com.sunshine.common.web.RemoteErrorMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Component
public class ModelManagerClient {

    private final WebClient webClient;

    public ModelManagerClient(WebClient.Builder builder) {
        this.webClient = builder
                .baseUrl("http://sunshine-resource-manager")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
        log.info("[BFF] ModelManager 客户端: baseUrl=http://sunshine-resource-manager");
    }

    // ---- providers ----

    public Mono<Map<String, Object>> listProviders(String tenantId) {
        return getWithOptionalTenant("/api/models/providers", tenantId);
    }

    public Mono<Map<String, Object>> createProvider(Map<String, Object> body) {
        return webClient.post()
                .uri("/api/models/providers")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> updateProvider(Long id, Map<String, Object> body) {
        return webClient.put()
                .uri("/api/models/providers/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> deleteProvider(Long id) {
        return webClient.delete()
                .uri("/api/models/providers/{id}", id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    // ---- definitions ----

    public Mono<Map<String, Object>> listDefinitions(String tenantId) {
        return getWithOptionalTenant("/api/models/definitions", tenantId);
    }

    public Mono<Map<String, Object>> createDefinition(Map<String, Object> body) {
        return webClient.post()
                .uri("/api/models/definitions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> updateDefinition(Long id, Map<String, Object> body) {
        return webClient.put()
                .uri("/api/models/definitions/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> toggleDefinition(Long id) {
        return webClient.post()
                .uri("/api/models/definitions/{id}/toggle", id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> deleteDefinition(Long id) {
        return webClient.delete()
                .uri("/api/models/definitions/{id}", id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    // ---- scenes ----

    public Mono<Map<String, Object>> listSceneKeys() {
        return get("/api/models/scenes/keys");
    }

    public Mono<Map<String, Object>> listScenes(String tenantId) {
        return getWithOptionalTenant("/api/models/scenes", tenantId);
    }

    /** body 可为单条对象或批量数组（对齐 resource-manager JsonNode upsert） */
    public Mono<Map<String, Object>> upsertScenes(Object body) {
        return webClient.put()
                .uri("/api/models/scenes")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body != null ? body : Map.of())
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    // ---- catalog（公开；不含 gateway） ----

    public Mono<Map<String, Object>> publicCatalog(String tenantId) {
        return getWithOptionalTenant("/api/models/catalog", tenantId);
    }

    private Mono<Map<String, Object>> getWithOptionalTenant(String path, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return get(path);
        }
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path(path)
                        .queryParam("tenantId", tenantId)
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
