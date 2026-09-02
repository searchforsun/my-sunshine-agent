package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
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

    private final WebClient webClient;

    @Value("${desensitize.enabled:true}")
    private boolean enabled;

    public DesensitizeClient(WebClient.Builder builder) {
        this.webClient = builder.baseUrl("http://sunshine-resource-manager").build();
        log.info("[DesensitizeClient] baseUrl=http://sunshine-resource-manager");
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
