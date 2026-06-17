package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@Component
public class DesensitizeClient {

    @Value("${desensitize.base-url:http://localhost:8600}")
    private String baseUrl;

    @Value("${desensitize.enabled:true}")
    private boolean enabled;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder().baseUrl(baseUrl).build();
        log.info("[DesensitizeClient] enabled={} baseUrl={}", enabled, baseUrl);
    }

    public String scrub(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return text;
        }
        return webClient.post()
                .uri("/api/desensitize/scrub")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", text))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<R<Map<String, String>>>() {})
                .map(r -> r.getData() != null ? r.getData().get("text") : text)
                .onErrorResume(e -> {
                    log.warn("[DesensitizeClient] scrub failed: {}", e.getMessage());
                    return Mono.just(text);
                })
                .block();
    }
}
