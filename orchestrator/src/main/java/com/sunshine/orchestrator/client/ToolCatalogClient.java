package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
import com.sunshine.orchestrator.catalog.ToolCatalogEntry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Component
public class ToolCatalogClient {

    @Value("${tool-manager.base-url:http://localhost:8210}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    public List<ToolCatalogEntry> fetchCatalog() {
        try {
            List<ToolCatalogEntry> entries = webClient.get()
                    .uri("/api/tools/catalog")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<List<ToolCatalogEntry>>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[ToolCatalogClient] fetch catalog failed: {}", e.getMessage());
                        return Mono.just(List.of());
                    })
                    .block();
            return entries != null ? entries : List.of();
        } catch (Exception e) {
            log.warn("[ToolCatalogClient] fetch catalog error: {}", e.getMessage());
            return List.of();
        }
    }
}
