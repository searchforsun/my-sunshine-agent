package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
import com.sunshine.orchestrator.catalog.SkillCatalogEntry;
import com.sunshine.orchestrator.catalog.SkillCatalogIndexEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class SkillCatalogClient {

    private final WebClient webClient;

    public SkillCatalogClient(WebClient.Builder builder) {
        this.webClient = builder.baseUrl("http://sunshine-resource-manager").build();
    }

    public List<SkillCatalogIndexEntry> fetchCatalogIndex(String tenantId) {
        try {
            List<SkillCatalogIndexEntry> entries = webClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/api/skills/catalog/index");
                        if (tenantId != null && !tenantId.isBlank()) {
                            uriBuilder.queryParam("tenantId", tenantId);
                        }
                        return uriBuilder.build();
                    })
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

    /** 启用 Skill 的 SKILL.md + scripts/ + references/ 文本材料（沙箱挂载） */
    public Map<String, String> fetchMaterial(String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return Map.of();
        }
        try {
            SkillMaterialPayload payload = webClient.get()
                    .uri("/api/skills/{id}/material", skillId.strip())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<SkillMaterialPayload>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[SkillCatalogClient] fetch material failed id={}: {}", skillId, e.getMessage());
                        return Mono.empty();
                    })
                    .block();
            if (payload == null || payload.files() == null) {
                return Map.of();
            }
            return Map.copyOf(payload.files());
        } catch (Exception e) {
            log.warn("[SkillCatalogClient] fetch material error id={}: {}", skillId, e.getMessage());
            return Map.of();
        }
    }

    /** 对齐 skill-manager SkillMaterialResponse */
    public record SkillMaterialPayload(Map<String, String> files) {}
}
