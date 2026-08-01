package com.sunshine.bff.controller;

import com.sunshine.bff.client.OrchestratorClient;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class AgentWorkspaceController {

    private final OrchestratorClient orchestratorClient;

    @GetMapping("/api/agent-workspaces")
    public Mono<Map<String, Object>> list(
            @RequestHeader("x-user-id") String userId,
            @RequestHeader("x-tenant-id") String tenantId) {
        return orchestratorClient.listWorkspaces(userId, tenantId);
    }

    @PostMapping("/api/agent-workspaces")
    public Mono<Map<String, Object>> create(
            @RequestBody Map<String, Object> body,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader("x-tenant-id") String tenantId) {
        return orchestratorClient.createWorkspace(body, userId, tenantId);
    }

    @DeleteMapping("/api/agent-workspaces/{id}")
    public Mono<Map<String, Object>> destroy(
            @PathVariable String id,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader("x-tenant-id") String tenantId) {
        return orchestratorClient.destroyWorkspace(id, userId, tenantId);
    }

    // ===== Git =====

    @GetMapping("/api/agent-workspaces/{id}/branches")
    public Mono<Map<String, Object>> listBranches(@PathVariable String id) {
        return orchestratorClient.listBranches(id);
    }

    @PostMapping("/api/agent-workspaces/{id}/branches")
    public Mono<Map<String, Object>> createBranch(@PathVariable String id,
                                                   @RequestBody Map<String, String> body) {
        return orchestratorClient.createBranch(id, body);
    }

    // ===== Checkout 管理（会话工作目录：/workspace/{checkoutId}，分支与目录一一对应） =====

    @GetMapping("/api/agent-workspaces/{id}/checkouts")
    public Mono<Map<String, Object>> listCheckouts(@PathVariable String id,
                                                    @RequestHeader("x-user-id") String userId,
                                                    @RequestHeader("x-tenant-id") String tenantId) {
        return orchestratorClient.listCheckouts(id, userId, tenantId);
    }

    @PostMapping("/api/agent-workspaces/{id}/checkouts")
    public Mono<Map<String, Object>> createCheckout(@PathVariable String id,
                                                     @RequestBody Map<String, String> body,
                                                     @RequestHeader("x-user-id") String userId,
                                                     @RequestHeader("x-tenant-id") String tenantId) {
        return orchestratorClient.createCheckout(id, body, userId, tenantId);
    }

    @PostMapping("/api/agent-workspaces/{id}/checkouts/ensure")
    public Mono<Map<String, Object>> ensureCheckout(@PathVariable String id,
                                                     @RequestBody Map<String, String> body,
                                                     @RequestHeader("x-user-id") String userId,
                                                     @RequestHeader("x-tenant-id") String tenantId) {
        return orchestratorClient.ensureCheckout(id, body, userId, tenantId);
    }

    @DeleteMapping("/api/agent-workspaces/{id}/checkouts/{checkoutId}")
    public Mono<Map<String, Object>> removeCheckout(@PathVariable String id,
                                                     @PathVariable String checkoutId,
                                                     @RequestHeader("x-user-id") String userId,
                                                     @RequestHeader("x-tenant-id") String tenantId) {
        return orchestratorClient.removeCheckout(id, checkoutId, userId, tenantId);
    }

    @GetMapping("/api/agent-workspaces/{id}/git/status")
    public Mono<Map<String, Object>> gitStatus(@PathVariable String id,
                                                @RequestParam("checkoutId") String checkoutId) {
        return orchestratorClient.gitStatus(id, checkoutId);
    }

    @PostMapping("/api/agent-workspaces/{id}/git/stage")
    public Mono<Map<String, Object>> gitStage(@PathVariable String id,
                                               @RequestParam("checkoutId") String checkoutId,
                                               @RequestBody Map<String, Object> body) {
        return orchestratorClient.gitStage(id, checkoutId, body);
    }

    @PostMapping("/api/agent-workspaces/{id}/git/commit")
    public Mono<Map<String, Object>> gitCommit(@PathVariable String id,
                                                @RequestParam("checkoutId") String checkoutId,
                                                @RequestBody Map<String, String> body) {
        return orchestratorClient.gitCommit(id, checkoutId, body);
    }

    @PostMapping("/api/agent-workspaces/{id}/git/push")
    public Mono<Map<String, Object>> gitPush(@PathVariable String id,
                                              @RequestParam("checkoutId") String checkoutId,
                                              @RequestHeader("x-user-id") String userId) {
        return orchestratorClient.gitPush(id, checkoutId, userId);
    }

    @PostMapping("/api/agent-workspaces/{id}/git/pull")
    public Mono<Map<String, Object>> gitPull(@PathVariable String id,
                                              @RequestParam("checkoutId") String checkoutId,
                                              @RequestHeader("x-user-id") String userId) {
        return orchestratorClient.gitPull(id, checkoutId, userId);
    }

    @PostMapping("/api/agent-workspaces/{id}/sync")
    public Mono<Map<String, Object>> syncWorkspace(@PathVariable String id,
                                                    @RequestHeader("x-user-id") String userId,
                                                    @RequestHeader("x-tenant-id") String tenantId) {
        return orchestratorClient.syncWorkspace(id, userId, tenantId);
    }

    // ===== 工作区文件浏览（无需 conversationId） =====

    @GetMapping("/api/agent-workspaces/{id}/sandbox/workspace")
    public Mono<Map<String, Object>> listFiles(
            @PathVariable String id,
            @RequestParam(value = "path", required = false, defaultValue = "/workspace") String path,
            @RequestHeader("x-tenant-id") String tenantId) {
        return orchestratorClient.listWsFiles(id, path, tenantId);
    }

    @GetMapping("/api/agent-workspaces/{id}/sandbox/workspace/content")
    public Mono<Map<String, Object>> readFile(
            @PathVariable String id,
            @RequestParam("path") String path,
            @RequestParam(value = "offset", required = false, defaultValue = "0") int offset,
            @RequestHeader("x-tenant-id") String tenantId) {
        return orchestratorClient.readWsFile(id, path, tenantId, offset);
    }
}
