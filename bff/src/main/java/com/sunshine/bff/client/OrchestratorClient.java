package com.sunshine.bff.client;

import com.sunshine.bff.model.ChatRequest;
import com.sunshine.bff.model.UpdateCheckoutRequest;
import com.sunshine.bff.model.UpdateTitleRequest;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import com.sunshine.common.web.RemoteErrorMapper;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OrchestratorClient {

    @Value("${orchestrator.base-url:http://localhost:8200}")
    private String baseUrl;

    private WebClient webClient;

    @PostConstruct
    public void init() {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                .build();
        log.info("[BFF] Orchestrator 客户端: baseUrl={}", baseUrl);
    }

    public Flux<ServerSentEvent<String>> stream(ChatRequest request, String userId, String tenantId) {
        return webClient.post()
                .uri("/chat/stream")
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .doOnSubscribe(s -> log.info("[BFF] 连接 Orchestrator SSE"))
                .doOnError(e -> log.error("[BFF] Orchestrator 连接异常", e));
    }

    public Mono<Map<String, Object>> confirmTool(
            com.sunshine.bff.model.ConfirmToolRequest request, String userId, String tenantId) {
        return webClient.post()
                .uri("/chat/confirm-tool")
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> confirmWorkflowNodeRecovery(
            com.sunshine.bff.model.ConfirmWorkflowNodeRecoveryRequest request, String userId, String tenantId) {
        return webClient.post()
                .uri("/chat/workflow-node-recovery")
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> confirmPlan(
            com.sunshine.bff.model.ConfirmPlanRequest request, String userId, String tenantId) {
        return webClient.post()
                .uri("/chat/confirm-plan")
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<List<Map<String, Object>>> listConversations(String userId, String tenantId) {
        return webClient.get()
                .uri("/conversations")
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    public Mono<Map<String, Object>> createConversation(Map<String, Object> body, String userId, String tenantId) {
        return webClient.post()
                .uri("/conversations")
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> getConversation(String id, String userId, String tenantId) {
        return webClient.get()
                .uri("/conversations/{id}", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> getConversationMessages(
            String id, int beforeSeq, int limit, String userId, String tenantId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder.path("/conversations/{id}/messages")
                        .queryParam("beforeSeq", beforeSeq)
                        .queryParam("limit", limit)
                        .build(id))
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> updateConversationTitle(
            String id, UpdateTitleRequest body, String userId, String tenantId) {
        return webClient.patch()
                .uri("/conversations/{id}", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> updateConversationCheckout(
            String id, UpdateCheckoutRequest body, String userId, String tenantId) {
        return webClient.patch()
                .uri("/conversations/{id}/checkout", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Void> deleteConversation(String id, String userId, String tenantId) {
        return webClient.delete()
                .uri("/conversations/{id}", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(Void.class);
    }

    public Mono<Map<String, Object>> listSandboxWorkspace(
            String conversationId, String path, String userId, String tenantId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/conversations/{id}/sandbox/workspace")
                        .queryParam("path", path != null ? path : "/workspace")
                        .build(conversationId))
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> readSandboxWorkspaceFile(
            String conversationId, String path, int offset, String userId, String tenantId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/conversations/{id}/sandbox/workspace/content")
                        .queryParam("path", path)
                        .queryParam("offset", offset)
                        .build(conversationId))
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> sandboxWorkspaceStatus(
            String conversationId, String userId, String tenantId) {
        return webClient.get()
                .uri("/conversations/{id}/sandbox/workspace/status", conversationId)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Flux<ServerSentEvent<String>> reconnectStream(
            String generationId, long afterSeq, String userId, String tenantId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/chat/stream/{generationId}")
                        .queryParam("afterSeq", afterSeq)
                        .build(generationId))
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .doOnSubscribe(s -> log.info("[BFF] 重连 generation SSE id={} afterSeq={}", generationId, afterSeq))
                .doOnError(e -> log.error("[BFF] generation 重连异常 id={}", generationId, e));
    }

    public Mono<Map<String, Object>> getGeneration(String id, String userId, String tenantId) {
        return webClient.get()
                .uri("/generations/{id}", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> cancelGeneration(String id, String userId, String tenantId) {
        return webClient.post()
                .uri("/generations/{id}/cancel", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> cancelSubagent(
            String generationId, String runId, String userId, String tenantId) {
        return webClient.post()
                .uri("/generations/{id}/subagents/{runId}/cancel", generationId, runId)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> cancelTool(
            String generationId, String toolRef, String userId, String tenantId) {
        return webClient.post()
                .uri("/generations/{id}/tools/{toolRef}/cancel", generationId, toolRef)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> getExecutionPlan(String planId, String userId, String tenantId) {
        return webClient.get()
                .uri("/execution-plans/{planId}", planId)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<List<Map<String, Object>>> listExecutionPlans(
            String conversationId, String userId, String tenantId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/execution-plans")
                        .queryParam("conversationId", conversationId)
                        .build())
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    public Mono<List<Map<String, Object>>> getExecutionPlanNodes(
            String planId, String userId, String tenantId) {
        return webClient.get()
                .uri("/execution-plans/{planId}/nodes", planId)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
    }

    public Mono<Map<String, Object>> listContextConversations(String userId, String tenantId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/admin/context/conversations")
                        .queryParam("userId", userId)
                        .queryParam("tenantId", tenantId != null ? tenantId : "default")
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> listContextL2(String userId, String tenantId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/admin/context/l2")
                        .queryParam("userId", userId)
                        .queryParam("tenantId", tenantId != null ? tenantId : "default")
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> updateContextL2(String id, Map<String, Object> body) {
        return webClient.put()
                .uri("/api/admin/context/l2/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body != null ? body : Map.of())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> voidContextL2(String id) {
        return webClient.post()
                .uri("/api/admin/context/l2/{id}/void", id)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> getContextL1(String convId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/admin/context/l1")
                        .queryParam("convId", convId)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> getContextL3Status(String userId, String tenantId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/admin/context/l3/status")
                        .queryParam("userId", userId)
                        .queryParam("tenantId", tenantId != null ? tenantId : "default")
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> listContextL3Entries(String convId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/admin/context/l3/entries")
                        .queryParam("convId", convId)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> runContextL3Gc() {
        return webClient.post()
                .uri("/api/admin/context/l3/gc")
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> reingestContextL3(String convId) {
        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/admin/context/l3/reingest")
                        .queryParam("convId", convId)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> listWorkspaces(String userId, String tenantId) {
        return webClient.get()
                .uri("/api/agent-workspaces")
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> createWorkspace(Map<String, Object> body, String userId, String tenantId) {
        return webClient.post()
                .uri("/api/agent-workspaces")
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> destroyWorkspace(String id, String userId, String tenantId) {
        return webClient.delete()
                .uri("/api/agent-workspaces/{id}", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> listBranches(String id, String userId, String tenantId) {
        return webClient.get()
                .uri("/api/agent-workspaces/{id}/branches", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> createBranch(String id, Map<String, String> body, String userId, String tenantId) {
        return webClient.post()
                .uri("/api/agent-workspaces/{id}/branches", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> listCheckouts(String id, String userId, String tenantId) {
        return webClient.get()
                .uri("/api/agent-workspaces/{id}/checkouts", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> createCheckout(String id, Map<String, String> body,
                                                     String userId, String tenantId) {
        return webClient.post()
                .uri("/api/agent-workspaces/{id}/checkouts", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> ensureCheckout(String id, Map<String, String> body,
                                                     String userId, String tenantId) {
        return webClient.post()
                .uri("/api/agent-workspaces/{id}/checkouts/ensure", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> removeCheckout(String id, String checkoutId,
                                                     String userId, String tenantId) {
        return webClient.delete()
                .uri("/api/agent-workspaces/{id}/checkouts/{checkoutId}", id, checkoutId)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> gitStatus(String id, String checkoutId, String userId, String tenantId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/agent-workspaces/{id}/git/status")
                        .queryParam("checkoutId", checkoutId)
                        .build(id))
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> gitDiff(String id, String checkoutId, String path, String userId, String tenantId) {
        return webClient.get()
                .uri(uriBuilder -> {
                    var b = uriBuilder.path("/api/agent-workspaces/{id}/git/diff")
                            .queryParam("checkoutId", checkoutId);
                    if (path != null && !path.isBlank()) {
                        b = b.queryParam("path", path);
                    }
                    return b.build(id);
                })
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> gitStage(String id, String checkoutId, Map<String, Object> body, String userId, String tenantId) {
        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/agent-workspaces/{id}/git/stage")
                        .queryParam("checkoutId", checkoutId)
                        .build(id))
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> gitRevert(String id, String checkoutId, Map<String, Object> body, String userId, String tenantId) {
        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/agent-workspaces/{id}/git/revert")
                        .queryParam("checkoutId", checkoutId)
                        .build(id))
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> gitUnstage(String id, String checkoutId, Map<String, Object> body, String userId, String tenantId) {
        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/agent-workspaces/{id}/git/unstage")
                        .queryParam("checkoutId", checkoutId)
                        .build(id))
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> gitCommit(String id, String checkoutId, Map<String, String> body, String userId, String tenantId) {
        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/agent-workspaces/{id}/git/commit")
                        .queryParam("checkoutId", checkoutId)
                        .build(id))
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> gitPush(String id, String checkoutId, String userId, String tenantId) {
        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/agent-workspaces/{id}/git/push")
                        .queryParam("checkoutId", checkoutId)
                        .build(id))
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> gitPull(String id, String checkoutId, String userId, String tenantId) {
        return webClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/agent-workspaces/{id}/git/pull")
                        .queryParam("checkoutId", checkoutId)
                        .build(id))
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> syncWorkspace(String id, String userId, String tenantId) {
        return webClient.post()
                .uri("/api/agent-workspaces/{id}/sync", id)
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> listWsFiles(String id, String path, String tenantId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/agent-workspaces/{id}/sandbox/workspace")
                        .queryParam("path", path)
                        .build(id))
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> listWsFileIndex(String id, String path, String tenantId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/agent-workspaces/{id}/sandbox/workspace/index")
                        .queryParam("path", path)
                        .build(id))
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> listSandboxFileIndex(
            String conversationId, String path, String userId, String tenantId) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/conversations/{id}/sandbox/workspace/index")
                        .queryParam("path", path != null ? path : "/workspace")
                        .build(conversationId))
                .header("x-user-id", userId)
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Mono<Map<String, Object>> readWsFile(String id, String path, String tenantId, int offset) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/agent-workspaces/{id}/sandbox/workspace/content")
                        .queryParam("path", path)
                        .queryParam("offset", offset)
                        .build(id))
                .header("x-tenant-id", tenantId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::toStatusException)
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private Mono<? extends Throwable> toStatusException(
            org.springframework.web.reactive.function.client.ClientResponse response) {
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(body -> Mono.error(RemoteErrorMapper.fromBody(response.statusCode().value(), body)));
    }
}
