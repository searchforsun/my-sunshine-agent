package com.sunshine.bff.controller;

import com.sunshine.bff.client.ChatImageClient;
import com.sunshine.bff.client.OrchestratorClient;
import com.sunshine.bff.model.ChatRequest;
import com.sunshine.bff.model.ConfirmToolRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * BFF 聊天控制器 — SSE 流式转发给 Orchestrator
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ChatController {

    private final OrchestratorClient client;
    private final ChatImageClient chatImageClient;

    /** 聊天图片上传透传 resource-manager；多模态走 BFF 聚合入口，链路同 skill 上传 */
    @PostMapping(value = "/api/chat/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> uploadChatImage(
            @RequestPart(value = "file") FilePart file,
            @RequestHeader(value = "x-user-id", required = false) String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return chatImageClient.upload(file, userId, tenantId);
    }

    @PostMapping(value = "/api/chat/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(
            @RequestBody ChatRequest request,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {

        String modeWire = request.getExecutionMode();
        log.info("[BFF] 用户 {} 发送消息 conv={} mode={} resume={}",
                userId, request.getConversationId(), modeWire, request.getResumeMessageId());

        return client.stream(request, userId, tenantId)
                .doOnComplete(() -> log.info("[BFF] 流式完成"))
                .doOnError(e -> log.error("[BFF] 异常", e));
    }

    @PostMapping("/api/chat/confirm-tool")
    public Mono<Map<String, Object>> confirmTool(
            @RequestBody ConfirmToolRequest request,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return client.confirmTool(request, userId, tenantId);
    }

    @PostMapping("/api/chat/workflow-node-recovery")
    public Mono<Map<String, Object>> confirmWorkflowNodeRecovery(
            @RequestBody com.sunshine.bff.model.ConfirmWorkflowNodeRecoveryRequest request,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return client.confirmWorkflowNodeRecovery(request, userId, tenantId);
    }
}
