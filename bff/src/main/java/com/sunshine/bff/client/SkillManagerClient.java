package com.sunshine.bff.client;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Component
public class SkillManagerClient {

    @Value("${skill-manager.base-url:http://localhost:8225}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        log.info("[BFF] SkillManager 客户端: baseUrl={}", baseUrl);
    }

    public Mono<Map<String, Object>> listSkills() {
        return get("/api/skills");
    }

    public Mono<Map<String, Object>> createSkill(Map<String, Object> body) {
        return webClient.post()
                .uri("/api/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> setEnabled(String id, boolean enabled) {
        return webClient.put()
                .uri("/api/skills/{id}/enable", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", enabled))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> updateSkill(String id, Map<String, Object> body) {
        return webClient.put()
                .uri("/api/skills/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> listVersions(String id) {
        return get("/api/skills/" + id + "/versions");
    }

    public Mono<Map<String, Object>> publish(String id, int version, String userId) {
        return webClient.post()
                .uri(uri -> uri.path("/api/skills/{id}/publish").queryParam("version", version).build(id))
                .headers(h -> applyUserId(h, userId))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> forkVersion(String id, int version, String userId) {
        return webClient.post()
                .uri("/api/skills/{id}/versions/{version}/fork", id, version)
                .headers(h -> applyUserId(h, userId))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> delete(String id) {
        return webClient.delete()
                .uri("/api/skills/{id}", id)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> deleteVersion(String id, int version) {
        return webClient.delete()
                .uri("/api/skills/{id}/versions/{version}", id, version)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> listFiles(String id, int version) {
        return get("/api/skills/" + id + "/versions/" + version + "/files");
    }

    public Mono<Map<String, Object>> readFile(String id, int version, String path) {
        return webClient.get()
                .uri(uri -> uri.path("/api/skills/{id}/versions/{version}/file")
                        .queryParam("path", path)
                        .build(id, version))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> writeFile(
            String id, int version, String path, String content, String userId) {
        return webClient.put()
                .uri(uri -> uri.path("/api/skills/{id}/versions/{version}/file")
                        .queryParam("path", path)
                        .build(id, version))
                .headers(h -> applyUserId(h, userId))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", content))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<ResponseEntity<byte[]>> downloadPackage(String id, int version) {
        return webClient.get()
                .uri("/api/skills/{id}/versions/{version}/download", id, version)
                .exchangeToMono(response -> {
                    if (response.statusCode().isError()) {
                        return response.bodyToMono(String.class)
                                .defaultIfEmpty("下载失败")
                                .flatMap(body -> Mono.error(new IllegalStateException(body)));
                    }
                    return response.toEntity(byte[].class);
                });
    }

    public Mono<Map<String, Object>> upload(
            String id,
            FilePart file,
            String content,
            String userId) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        if (file != null) {
            builder.asyncPart("file", file.content(), org.springframework.core.io.buffer.DataBuffer.class)
                    .filename(file.filename())
                    .contentType(file.headers().getContentType());
        }
        if (StringUtils.hasText(content)) {
            builder.part("content", content);
        }
        return webClient.post()
                .uri("/api/skills/{id}/upload", id)
                .headers(h -> applyUserId(h, userId))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private static void applyUserId(org.springframework.http.HttpHeaders headers, String userId) {
        if (StringUtils.hasText(userId)) {
            headers.set("x-user-id", userId);
        }
    }

    public Mono<Map<String, Object>> catalogIndex() {
        return get("/api/skills/catalog/index");
    }

    public Mono<Map<String, Object>> catalogDetail(String id) {
        return get("/api/skills/" + id + "/catalog");
    }

    private Mono<Map<String, Object>> get(String path) {
        return webClient.get()
                .uri(path)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}
