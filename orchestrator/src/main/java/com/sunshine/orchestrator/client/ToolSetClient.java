package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
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
public class ToolSetClient {

    @Value("${tool-manager.base-url:http://localhost:8210}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    public List<String> fetchReactDefault(String tenantId) {
        return fetchToolIds("react-default", tenantId).toolIds();
    }

    public List<String> fetchPlanWorkflow(String tenantId) {
        return fetchToolIds("plan-workflow", tenantId).toolIds();
    }

    public List<String> fetchPlanWorkflowCritical(String tenantId) {
        return fetchToolIds("plan-workflow", tenantId).criticalToolIds();
    }

    private ToolSetToolIdsResponse fetchToolIds(String kind, String tenantId) {
        try {
            ToolSetToolIdsResponse response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/tools/sets/" + kind + "/tool-ids")
                            .queryParam("tenantId", tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default")
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<ToolSetToolIdsResponse>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[ToolSetClient] fetch {} tool-ids failed tenant={}: {}", kind, tenantId, e.getMessage());
                        return Mono.empty();
                    })
                    .block();
            if (response == null) {
                return new ToolSetToolIdsResponse(List.of(), List.of());
            }
            List<String> toolIds = response.toolIds() != null ? List.copyOf(response.toolIds()) : List.of();
            List<String> critical = response.criticalToolIds() != null ? List.copyOf(response.criticalToolIds()) : List.of();
            return new ToolSetToolIdsResponse(toolIds, critical);
        } catch (Exception e) {
            log.warn("[ToolSetClient] fetch {} tool-ids error tenant={}: {}", kind, tenantId, e.getMessage());
            return new ToolSetToolIdsResponse(List.of(), List.of());
        }
    }
}
