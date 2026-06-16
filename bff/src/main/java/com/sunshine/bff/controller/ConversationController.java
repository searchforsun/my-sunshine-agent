package com.sunshine.bff.controller;

import com.sunshine.bff.client.OrchestratorClient;
import com.sunshine.bff.model.UpdateTitleRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ConversationController {

    private final OrchestratorClient client;

    @GetMapping("/api/conversations")
    public Mono<List<Map<String, Object>>> list(
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return client.listConversations(userId, tenantId);
    }

    @PostMapping("/api/conversations")
    public Mono<Map<String, Object>> create(
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return client.createConversation(userId, tenantId);
    }

    @GetMapping("/api/conversations/{id}")
    public Mono<Map<String, Object>> get(
            @PathVariable String id,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return client.getConversation(id, userId, tenantId);
    }

    @PatchMapping("/api/conversations/{id}")
    public Mono<Map<String, Object>> updateTitle(
            @PathVariable String id,
            @RequestBody UpdateTitleRequest body,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return client.updateConversationTitle(id, body, userId, tenantId);
    }

    @DeleteMapping("/api/conversations/{id}")
    public Mono<Void> delete(
            @PathVariable String id,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return client.deleteConversation(id, userId, tenantId);
    }
}
