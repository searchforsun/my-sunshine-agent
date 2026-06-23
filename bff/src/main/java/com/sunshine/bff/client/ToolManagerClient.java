package com.sunshine.bff.client;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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
        log.info("[BFF] ToolManager 客户端: baseUrl={}", baseUrl);
    }

    public Mono<Map<String, Object>> catalog() {
        return webClient.get()
                .uri("/api/tools/catalog")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}
