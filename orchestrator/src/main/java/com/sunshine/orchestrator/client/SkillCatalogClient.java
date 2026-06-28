package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
import com.sunshine.orchestrator.catalog.SkillCatalogEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
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
public class SkillCatalogClient {

    @Value("${skill-manager.base-url:http://localhost:8225}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    public List<SkillCatalogIndexEntry> fetchCatalogIndex() {
        try {
            List<SkillCatalogIndexEntry> entries = webClient.get()
                    .uri("/api/skills/catalog/index")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<List<SkillCatalogIndexEntry>>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[SkillCatalogClient] fetch catalog index failed: {}", e.getMessage());
                        return Mono.just(List.of());
                    })
                    .block();
            return entries != null ? entries : List.of();
        } catch (Exception e) {
            log.warn("[SkillCatalogClient] fetch catalog index error: {}", e.getMessage());
            return List.of();
        }
    }

    public Optional<SkillCatalogEntry> fetchSkillDetail(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return Optional.empty();
        }
        try {
            SkillCatalogEntry entry = webClient.get()
                    .uri("/api/skills/{id}/catalog", skillId.strip())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<SkillCatalogEntry>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[SkillCatalogClient] fetch skill detail failed id={}: {}", skillId, e.getMessage());
                        return Mono.empty();
                    })
                    .block();
            return Optional.ofNullable(entry);
        } catch (Exception e) {
            log.warn("[SkillCatalogClient] fetch skill detail error id={}: {}", skillId, e.getMessage());
            return Optional.empty();
        }
    }
}
