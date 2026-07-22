package com.sunshine.bff.controller;

import com.sunshine.bff.client.OrchestratorClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/** BFF 透传 orchestrator /api/admin/context/*（仿 Experts 管理页） */
@RestController
@RequiredArgsConstructor
public class ContextAdminController {

    private final OrchestratorClient orchestratorClient;

    @GetMapping("/api/admin/context/conversations")
    public Mono<Map<String, Object>> listConversations(
            @RequestParam String userId,
            @RequestParam(required = false, defaultValue = "default") String tenantId) {
        return orchestratorClient.listContextConversations(userId, tenantId);
    }

    @GetMapping("/api/admin/context/l2")
    public Mono<Map<String, Object>> listL2(
            @RequestParam String userId,
            @RequestParam(required = false, defaultValue = "default") String tenantId) {
        return orchestratorClient.listContextL2(userId, tenantId);
    }

    @PutMapping("/api/admin/context/l2/{id}")
    public Mono<Map<String, Object>> updateL2(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        return orchestratorClient.updateContextL2(id, body);
    }

    @PostMapping("/api/admin/context/l2/{id}/void")
    public Mono<Map<String, Object>> voidL2(@PathVariable String id) {
        return orchestratorClient.voidContextL2(id);
    }

    @GetMapping("/api/admin/context/l1")
    public Mono<Map<String, Object>> getL1(@RequestParam String convId) {
        return orchestratorClient.getContextL1(convId);
    }

    @GetMapping("/api/admin/context/l3/status")
    public Mono<Map<String, Object>> l3Status(
            @RequestParam String userId,
            @RequestParam(required = false, defaultValue = "default") String tenantId) {
        return orchestratorClient.getContextL3Status(userId, tenantId);
    }

    @GetMapping("/api/admin/context/l3/entries")
    public Mono<Map<String, Object>> listL3Entries(@RequestParam String convId) {
        return orchestratorClient.listContextL3Entries(convId);
    }

    @PostMapping("/api/admin/context/l3/gc")
    public Mono<Map<String, Object>> gc() {
        return orchestratorClient.runContextL3Gc();
    }

    @PostMapping("/api/admin/context/l3/reingest")
    public Mono<Map<String, Object>> reingest(@RequestParam String convId) {
        return orchestratorClient.reingestContextL3(convId);
    }
}
