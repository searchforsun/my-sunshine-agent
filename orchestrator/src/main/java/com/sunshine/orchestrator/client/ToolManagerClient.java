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
public class ToolManagerClient {

    @Value("${tool-manager.base-url:http://localhost:8210}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder().baseUrl(baseUrl).build();
        log.info("[ToolManagerClient] baseUrl={}", baseUrl);
    }

    public String invoke(String name, Map<String, String> params) {
        Map<String, Object> body = Map.of(
                "name", name,
                "params", params != null ? params : Map.of());
        return webClient.post()
                .uri("/api/tools/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<R<String>>() {})
                .map(R::getData)
                .onErrorResume(e -> {
                    log.warn("[ToolManagerClient] invoke {} failed: {}", name, e.getMessage());
                    return Mono.just("工具调用失败: " + e.getMessage());
                })
                .block();
    }
}
