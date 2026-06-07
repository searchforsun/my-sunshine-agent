package com.sunshine.orchestrator.controller;

import com.sunshine.orchestrator.agent.IntentRouter;
import com.sunshine.orchestrator.agent.SunshineAgent;
import com.sunshine.orchestrator.client.LlmGatewayClient;
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
 * Orchestrator 聊天控制器 — 意图分流
 * simple → 直连 LLM Gateway 逐 token 流式（快）
 * knowledge → AgentScope ReActAgent + 知识库检索
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final IntentRouter intentRouter;
    private final LlmGatewayClient llmGateway;
    private final SunshineAgent sunshineAgent;

    @PostMapping(value = "/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatStream(
            @RequestBody ChatMessage msg,
            @RequestHeader(value = "x-user-id", defaultValue = "anonymous") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {

        log.info("[Orchestrator] user={}, len={}", userId,
                msg.getContent() != null ? msg.getContent().length() : 0);

        return intentRouter.classify(msg.getContent())
                .flatMapMany(intent -> {
                    if ("knowledge".equals(intent)) {
                        log.info("[Orchestrator] → Agent 路径（知识库检索）");
                        return sunshineAgent.chat(msg.getContent(), userId, tenantId);
                    } else {
                        log.info("[Orchestrator] → 直连流式（简单对话）");
                        return llmGateway.streamDirectly(msg.getContent());
                    }
                })
                .map(chunk -> ServerSentEvent.<String>builder()
                        .id(UUID.randomUUID().toString().substring(0, 8))
                        .data(chunk)
                        .build())
                .doOnComplete(() -> log.info("[Orchestrator] 流式完成"))
                .doOnError(e -> log.error("[Orchestrator] 异常", e));
    }
}
