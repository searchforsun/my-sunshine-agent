package com.sunshine.bff.controller;

import com.sunshine.bff.client.OrchestratorClient;
import com.sunshine.bff.model.ChatRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * BFF 聊天控制器 — SSE 流式转发给 Orchestrator
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final OrchestratorClient client;

    @PostMapping(value = "/api/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "x-user-id", defaultValue = "anonymous") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {

        log.info("[BFF] 用户 {} 发送消息 conv={} resume={}",
                userId, request.getConversationId(), request.getResumeMessageId());

        return client.stream(request, userId, tenantId)
                .doOnComplete(() -> log.info("[BFF] 流式完成"))
                .doOnError(e -> log.error("[BFF] 异常", e));
    }
}
