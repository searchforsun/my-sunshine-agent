package com.sunshine.orchestrator.client;

import com.sunshine.common.core.result.R;
import com.sunshine.common.tool.PlanWorkflowExecutionPolicy;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class ExecutionModePolicyClient {

    @Value("${tool-manager.base-url:http://localhost:8210}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    void init() {
        webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    public PlanWorkflowExecutionPolicy fetchPlanWorkflow(String tenantId) {
        try {
            PlanWorkflowExecutionPolicy response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/admin/tools/modes/plan-workflow")
                            .queryParam("tenantId", tenantId != null && !tenantId.isBlank() ? tenantId.strip() : "default")
                            .build())
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<R<PlanWorkflowExecutionPolicy>>() {})
                    .map(R::getData)
                    .onErrorResume(e -> {
                        log.warn("[ExecutionModePolicyClient] fetch plan-workflow failed tenant={}: {}",
                                tenantId, e.getMessage());
                        return Mono.empty();
                    })
                    .block();
            if (response == null) {
                return PlanWorkflowExecutionPolicy.platformDefault();
            }
            return response;
        } catch (Exception e) {
            log.warn("[ExecutionModePolicyClient] fetch plan-workflow error tenant={}: {}", tenantId, e.getMessage());
            return PlanWorkflowExecutionPolicy.platformDefault();
        }
    }
}
