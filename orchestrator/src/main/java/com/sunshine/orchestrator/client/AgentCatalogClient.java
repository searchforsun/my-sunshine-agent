package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
import com.sunshine.orchestrator.catalog.AgentCatalogEntry;
import com.sunshine.orchestrator.catalog.AgentCatalogIndexEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class AgentCatalogClient {

    private final WebClient webClient;

    public AgentCatalogClient(WebClient.Builder builder) {
        this.webClient = builder.baseUrl("http://sunshine-resource-manager").build();
    }

    public List<AgentCatalogIndexEntry> fetchCatalogIndex(String tenantId) {
        try {
            List<AgentCatalogIndexEntry> entries = webClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/api/agents/catalog/index");
                        if (tenantId != null && !tenantId.isBlank()) {
                            uriBuilder.queryParam("tenantId", tenantId);
                        }
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<List<AgentCatalogIndexEntry>>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[AgentCatalogClient] fetch catalog index failed: {}", e.getMessage());
                        return Mono.just(List.of());
                    })
                    .block();
            return entries != null ? entries : List.of();
        } catch (Exception e) {
            log.warn("[AgentCatalogClient] fetch catalog index error: {}", e.getMessage());
            return List.of();
        }
    }

    public Optional<AgentCatalogEntry> fetchAgentDetail(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return Optional.empty();
        }
        try {
            AgentCatalogEntry entry = webClient.get()
                    .uri("/api/agents/{id}/catalog", agentId.strip())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<AgentCatalogEntry>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[AgentCatalogClient] fetch agent detail failed id={}: {}", agentId, e.getMessage());
                        return Mono.empty();
                    })
                    .block();
            return Optional.ofNullable(entry);
        } catch (Exception e) {
            log.warn("[AgentCatalogClient] fetch agent detail error id={}: {}", agentId, e.getMessage());
            return Optional.empty();
        }
    }
}
