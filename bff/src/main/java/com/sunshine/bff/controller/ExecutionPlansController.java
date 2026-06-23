package com.sunshine.bff.controller;

import com.sunshine.bff.client.OrchestratorClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ExecutionPlansController {

    private final OrchestratorClient orchestratorClient;

    @GetMapping("/api/execution-plans/{planId}")
    public Mono<Map<String, Object>> get(
            @PathVariable String planId,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return orchestratorClient.getExecutionPlan(planId, userId, tenantId);
    }

    @GetMapping("/api/execution-plans")
    public Mono<List<Map<String, Object>>> list(
            @RequestParam("conversationId") String conversationId,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return orchestratorClient.listExecutionPlans(conversationId, userId, tenantId);
    }

    @GetMapping("/api/execution-plans/{planId}/nodes")
    public Mono<List<Map<String, Object>>> nodes(
            @PathVariable String planId,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return orchestratorClient.getExecutionPlanNodes(planId, userId, tenantId);
    }
}
