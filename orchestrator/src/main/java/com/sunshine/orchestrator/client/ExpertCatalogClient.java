package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
import com.sunshine.orchestrator.catalog.ExpertCatalogEntry;
import com.sunshine.orchestrator.catalog.ExpertCatalogIndexEntry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class ExpertCatalogClient {
    @Value("${expert-manager.base-url:http://localhost:8235}")
    private String baseUrl;
    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    public List<ExpertCatalogIndexEntry> fetchCatalogIndex() {
        try {
            List<ExpertCatalogIndexEntry> entries = webClient.get()
                    .uri("/api/experts/catalog/index")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<List<ExpertCatalogIndexEntry>>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[ExpertCatalogClient] fetch catalog index failed: {}", e.getMessage());
                        return Mono.just(List.of());
                    })
                    .block();
            return entries != null ? entries : List.of();
        } catch (Exception e) {
            log.warn("[ExpertCatalogClient] fetch catalog index error: {}", e.getMessage());
            return List.of();
        }
    }

    public Optional<ExpertCatalogEntry> fetchExpertDetail(String expertId) {
        if (expertId == null || expertId.isBlank()) {
            return Optional.empty();
        }
        try {
            ExpertCatalogEntry entry = webClient.get()
                    .uri("/api/experts/{id}/catalog", expertId.strip())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<ExpertCatalogEntry>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[ExpertCatalogClient] fetch expert detail failed id={}: {}", expertId, e.getMessage());
                        return Mono.empty();
                    })
                    .block();
            return Optional.ofNullable(entry);
        } catch (Exception e) {
            log.warn("[ExpertCatalogClient] fetch expert detail error id={}: {}", expertId, e.getMessage());
            return Optional.empty();
        }
    }
}
