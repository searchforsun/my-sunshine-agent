package com.sunshine.orchestrator.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.common.core.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class WorkflowManagerClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public WorkflowManagerClient(WebClient.Builder builder, ObjectMapper objectMapper) {
        this.webClient = builder
                .baseUrl("http://sunshine-workflow-manager")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
        this.objectMapper = objectMapper;
        log.info("[WorkflowManagerClient] baseUrl=http://sunshine-workflow-manager");
    }

    public List<WorkflowCatalogEntryDto> fetchCatalog() {
        try {
            List<WorkflowCatalogEntryDto> entries = webClient.get()
                    .uri("/api/workflows/catalog")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<List<WorkflowCatalogEntryDto>>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[WorkflowManagerClient] fetch catalog failed: {}", e.getMessage());
                        return Mono.just(List.of());
                    })
                    .block();
            return entries != null ? entries : List.of();
        } catch (Exception e) {
            log.warn("[WorkflowManagerClient] fetch catalog error: {}", e.getMessage());
            return List.of();
        }
    }

    public Optional<WorkflowPublishedDto> fetchPublished(String workflowId) {
        if (!StringUtils.hasText(workflowId)) {
            return Optional.empty();
        }
        try {
            WorkflowPublishedDto published = webClient.get()
                    .uri("/api/workflows/{id}/published", workflowId.strip())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<WorkflowPublishedDto>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[WorkflowManagerClient] fetch published failed id={}: {}", workflowId, e.getMessage());
                        return Mono.empty();
                    })
                    .block();
            return Optional.ofNullable(published);
        } catch (Exception e) {
            log.warn("[WorkflowManagerClient] fetch published error id={}: {}", workflowId, e.getMessage());
            return Optional.empty();
        }
    }

    public WorkflowNodeDefaultsDto fetchNodeDefaults() {
        try {
            WorkflowNodeDefaultsDto dto = webClient.get()
                    .uri("/api/workflows/node-defaults")
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<WorkflowNodeDefaultsDto>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[WorkflowManagerClient] fetch node-defaults failed: {}", e.getMessage());
                        return Mono.empty();
                    })
                    .block();
            return dto;
        } catch (Exception e) {
            log.warn("[WorkflowManagerClient] fetch node-defaults error: {}", e.getMessage());
            return null;
        }
    }

    public String planToJson(Map<String, Object> plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (Exception e) {
            throw new IllegalStateException("Plan JSON 序列化失败: " + e.getMessage());
        }
    }

    public record WorkflowCatalogEntryDto(
            String id,
            String mode,
            String displayName,
            String description,
            String kind,
            List<String> examples,
            List<String> nodes,
            String intentAfter) {
    }

    public record WorkflowNodeRetryDefaultsDto(
            int maxAttempts,
            long backoffMs,
            String onFailure) {
    }

    public record WorkflowNodeDefaultsDto(
            WorkflowNodeRetryDefaultsDto defaults,
            Map<String, WorkflowNodeRetryDefaultsDto> byType,
            String criticalOnFailure,
            double backoffMultiplier,
            List<String> retryOnErrorClass) {
    }

    public record WorkflowPublishedDto(
            String workflowId,
            int version,
            Map<String, Object> plan,
            Map<String, Object> catalogMeta) {
    }
}
