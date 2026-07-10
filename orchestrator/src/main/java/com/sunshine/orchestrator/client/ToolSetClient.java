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
        return fetchToolSet("/api/admin/tools/sets/react-default", tenantId, "react-default");
    }

    public List<String> fetchPlanWorkflowCritical(String tenantId) {
        return fetchToolSet("/api/admin/tools/sets/plan-workflow-critical", tenantId, "plan-workflow-critical");
    }

    private List<String> fetchToolSet(String path, String tenantId, String label) {
        try {
            ToolSetResponse response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(path)
                            .queryParam("tenantId", tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default")
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<ToolSetResponse>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[ToolSetClient] fetch {} failed tenant={}: {}", label, tenantId, e.getMessage());
                        return Mono.empty();
                    })
                    .block();
            if (response == null || response.toolIds() == null) {
                return List.of();
            }
            return List.copyOf(response.toolIds());
        } catch (Exception e) {
            log.warn("[ToolSetClient] fetch {} error tenant={}: {}", label, tenantId, e.getMessage());
            return List.of();
        }
    }
}
