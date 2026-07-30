package com.sunshine.bff.controller;

import com.sunshine.bff.client.AgentManagerClient;
import com.sunshine.common.core.exception.BizException;
import com.sunshine.common.core.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** BFF 透传 agent-manager Catalog / Admin */
@RestController
@RequiredArgsConstructor
public class AgentsController {

    private final AgentManagerClient agentManagerClient;

    @GetMapping("/api/agents")
    public Mono<Map<String, Object>> listAgents() {
        return agentManagerClient.listAgents();
    }

    @PostMapping("/api/agents")
    public Mono<Map<String, Object>> createAgent(@RequestBody Map<String, Object> body) {
        return agentManagerClient.createAgent(body);
    }

    @PutMapping("/api/agents/{id}")
    public Mono<Map<String, Object>> updateAgent(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return agentManagerClient.updateAgent(id, body);
    }

    @PutMapping("/api/agents/{id}/enable")
    public Mono<Map<String, Object>> enableAgent(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return agentManagerClient.setEnabled(id, enabled);
    }

    @DeleteMapping("/api/agents/{id}")
    public Mono<Map<String, Object>> deleteAgent(@PathVariable String id) {
        return agentManagerClient.deleteAgent(id);
    }

    @GetMapping("/api/agents/catalog/index")
    public Mono<Map<String, Object>> agentCatalogIndex() {
        return agentManagerClient.catalogIndex();
    }

    @GetMapping("/api/agents/catalog")
    public Mono<Map<String, Object>> agentCatalogRemoved() {
        return Mono.error(new BizException(CommonErrorCode.GONE));
    }

    @GetMapping("/api/agents/external/card-prefill")
    public Mono<Map<String, Object>> fetchAgentCard(@RequestParam String agentCardUrl) {
        return agentManagerClient.fetchAgentCard(agentCardUrl);
    }

    @GetMapping("/api/agents/{id}/catalog")
    public Mono<Map<String, Object>> agentCatalogDetail(@PathVariable String id) {
        return agentManagerClient.catalogDetail(id);
    }
}
