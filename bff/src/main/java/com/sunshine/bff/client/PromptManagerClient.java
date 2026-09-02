package com.sunshine.bff.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import com.sunshine.common.web.RemoteErrorMapper;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Component
public class PromptManagerClient {

    private final WebClient webClient;

    public PromptManagerClient(WebClient.Builder builder) {
        this.webClient = builder
                .baseUrl("http://sunshine-resource-manager")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
        log.info("[BFF] PromptManager 客户端: baseUrl=http://sunshine-resource-manager");
    }

    public Mono<Map<String, Object>> listPrompts(String kind, Boolean enabled) {
        return webClient.get()
                .uri(uri -> {
                    var builder = uri.path("/api/prompts");
                    if (StringUtils.hasText(kind)) {
                        builder.queryParam("kind", kind);
                    }
                    if (enabled != null) {
                        builder.queryParam("enabled", enabled);
                    }
                    return builder.build();
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> getPrompt(String id) {
        return get("/api/prompts/" + id);
    }

    public Mono<Map<String, Object>> createPrompt(Map<String, Object> body) {
        return post("/api/prompts", body);
    }

    public Mono<Map<String, Object>> updatePrompt(String id, Map<String, Object> body) {
        return webClient.put()
                .uri("/api/prompts/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> setEnabled(String id, Map<String, Object> body) {
        return webClient.put()
                .uri("/api/prompts/{id}/enable", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> listVersions(String id) {
        return get("/api/prompts/" + id + "/versions");
    }

    public Mono<Map<String, Object>> addVersion(String id, Map<String, Object> body) {
        return webClient.post()
                .uri("/api/prompts/{id}/versions", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> publish(String id, Map<String, Object> body) {
        if (body != null && !body.isEmpty()) {
            return webClient.post()
                    .uri("/api/prompts/{id}/publish", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::toBizError)
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
        }
        return webClient.post()
                .uri("/api/prompts/{id}/publish", id)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> rollback(String id, Map<String, Object> body) {
        return webClient.post()
                .uri("/api/prompts/{id}/rollback", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    /** GET /api/prompts/catalog — 字面量路径，勿与 GET /{id} 混用 */
    public Mono<Map<String, Object>> catalog() {
        return get("/api/prompts/catalog");
    }

    /** POST /api/prompts/routing/validate */
    public Mono<Map<String, Object>> routingValidate(Map<String, Object> body) {
        return post("/api/prompts/routing/validate", body);
    }

    /** POST /api/prompts/routing/dry-run */
    public Mono<Map<String, Object>> routingDryRun(Map<String, Object> body) {
        return post("/api/prompts/routing/dry-run", body);
    }

    private Mono<Map<String, Object>> get(String path) {
        return webClient.get()
                .uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private Mono<Map<String, Object>> post(String path, Map<String, Object> body) {
        if (body != null) {
            return webClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::toBizError)
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
        }
        return webClient.post()
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
