package com.sunshine.bff.controller;

import com.sunshine.bff.client.OrchestratorClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class GenerationController {

    private final OrchestratorClient client;

    @GetMapping("/api/generations/{id}")
    public Mono<Map<String, Object>> getStatus(
            @PathVariable String id,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return client.getGeneration(id, userId, tenantId);
    }

    @PostMapping("/api/generations/{id}/cancel")
    public Mono<Map<String, Object>> cancel(
            @PathVariable String id,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        log.info("[BFF] 取消 generation id={} user={}", id, userId);
        return client.cancelGeneration(id, userId, tenantId);
    }

    @PostMapping("/api/generations/{id}/subagents/{runId}/cancel")
    public Mono<Map<String, Object>> cancelSubagent(
            @PathVariable String id,
            @PathVariable String runId,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        log.info("[BFF] 取消子任务 generation={} runId={} user={}", id, runId, userId);
        return client.cancelSubagent(id, runId, userId, tenantId);
    }

    @PostMapping("/api/generations/{id}/decisions/{token}/resolve")
    public Mono<Map<String, Object>> resolveDecision(
            @PathVariable String id,
            @PathVariable String token,
            @RequestBody Map<String, Object> body,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return client.resolveDecision(id, token, body, userId, tenantId);
    }

    @PostMapping("/api/generations/{id}/tools/{toolRef}/cancel")
    public Mono<Map<String, Object>> cancelTool(
            @PathVariable String id,
            @PathVariable String toolRef,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        log.info("[BFF] 取消工具 generation={} toolRef={} user={}", id, toolRef, userId);
        return client.cancelTool(id, toolRef, userId, tenantId);
    }

    @GetMapping(value = "/api/chat/stream/{generationId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> reconnectStream(
            @PathVariable String generationId,
            @RequestParam(defaultValue = "0") long afterSeq,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        log.info("[BFF] 重连 generation stream id={} afterSeq={} user={}", generationId, afterSeq, userId);
        return client.reconnectStream(generationId, afterSeq, userId, tenantId)
                .doOnComplete(() -> log.info("[BFF] generation 重连流完成 id={}", generationId))
                .doOnError(e -> log.error("[BFF] generation 重连流异常 id={}", generationId, e));
    }
}
