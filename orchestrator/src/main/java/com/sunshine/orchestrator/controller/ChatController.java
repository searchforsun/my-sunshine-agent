package com.sunshine.orchestrator.controller;

import com.sunshine.orchestrator.agent.SunshineAgent;
import com.sunshine.orchestrator.model.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Orchestrator 聊天控制器 — Agent 对话入口
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final SunshineAgent agent;

    @PostMapping(value = "/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestBody ChatMessage msg,
            @RequestHeader(value = "x-user-id", defaultValue = "anonymous") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {

        log.info("[Orchestrator] 用户 {} 发送消息", userId);

        return agent.chat(msg.getContent(), userId, tenantId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .id(UUID.randomUUID().toString().substring(0, 8))
                        .data(chunk)
                        .build())
                .doOnComplete(() -> log.info("[Orchestrator] 响应完成"))
                .doOnError(e -> log.error("[Orchestrator] 异常", e));
    }
}
