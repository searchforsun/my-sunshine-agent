package com.sunshine.bff.controller;

import com.sunshine.bff.client.OrchestratorClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** 审计查询 — 透传 orchestrator /api/audit/* */
@RestController
@RequiredArgsConstructor
public class AuditController {

    private final OrchestratorClient orchestratorClient;

    @GetMapping("/api/audit/peer-run/{messageId}")
    public Mono<Map<String, Object>> peerRun(
            @PathVariable String messageId,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return orchestratorClient.getPeerRun(messageId, userId, tenantId);
    }
}
