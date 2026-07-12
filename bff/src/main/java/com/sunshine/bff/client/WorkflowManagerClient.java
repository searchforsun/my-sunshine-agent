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
public class WorkflowManagerClient {

    @Value("${workflow-manager.base-url:http://localhost:8230}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
        log.info("[BFF] WorkflowManager 客户端: baseUrl={}", baseUrl);
    }

    public Mono<Map<String, Object>> catalog() {
        return get("/api/workflows/catalog");
    }

    public Mono<Map<String, Object>> nodeDefaults() {
        return get("/api/workflows/node-defaults");
    }

    public Mono<Map<String, Object>> list() {
        return get("/api/workflows");
    }

    public Mono<Map<String, Object>> published(String id) {
        return get("/api/workflows/" + id + "/published");
    }

    public Mono<Map<String, Object>> editable(String id) {
        return get("/api/workflows/" + id + "/editable");
    }

    public Mono<Map<String, Object>> versions(String id) {
        return get("/api/workflows/" + id + "/versions");
    }

    public Mono<Map<String, Object>> versionDetail(String id, int version) {
        return get("/api/workflows/" + id + "/versions/" + version);
    }

    public Mono<Map<String, Object>> exportVersion(String id, int version) {
        return get("/api/workflows/" + id + "/versions/" + version + "/export");
    }

    public Mono<Map<String, Object>> create(Map<String, Object> body) {
        return post("/api/workflows", body);
    }

    public Mono<Map<String, Object>> update(String id, Map<String, Object> body) {
        return put("/api/workflows/" + id, body);
    }

    public Mono<Map<String, Object>> enable(String id, Map<String, Object> body) {
        return put("/api/workflows/" + id + "/enable", body);
    }

    public Mono<Map<String, Object>> saveDraft(String id, Map<String, Object> body) {
        return put("/api/workflows/" + id + "/draft", body);
    }

    public Mono<Map<String, Object>> validatePlan(Map<String, Object> body) {
        return post("/api/workflows/plan/validate", body);
    }

    public Mono<Map<String, Object>> publish(String id, Integer version) {
        return webClient.post()
                .uri(uri -> {
                    var b = uri.path("/api/workflows/{id}/publish");
                    if (version != null) {
                        b.queryParam("version", version);
                    }
                    return b.build(id);
                })
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> fork(String id, int version) {
        return post("/api/workflows/" + id + "/versions/" + version + "/fork", Map.of());
    }

    public Mono<Map<String, Object>> importPackage(Map<String, Object> body) {
        return post("/api/workflows/import", body);
    }

    public Mono<Map<String, Object>> delete(String id) {
        return deleteReq("/api/workflows/" + id);
    }

    public Mono<Map<String, Object>> deleteVersion(String id, int version) {
        return deleteReq("/api/workflows/" + id + "/versions/" + version);
    }

    private Mono<Map<String, Object>> get(String path) {
        return webClient.get()
                .uri(path)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private Mono<Map<String, Object>> post(String path, Map<String, Object> body) {
        return webClient.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private Mono<Map<String, Object>> put(String path, Map<String, Object> body) {
        return webClient.put()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toBizError)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private Mono<Map<String, Object>> deleteReq(String path) {
        return webClient.delete()
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
