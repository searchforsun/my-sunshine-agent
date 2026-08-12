package com.sunshine.orchestrator.controller;

import com.sunshine.orchestrator.config.ReactiveBlocking;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.dto.ConversationDetailDto;
import com.sunshine.orchestrator.conversation.dto.ConversationPageDto;
import com.sunshine.orchestrator.conversation.dto.ConversationSearchDto;
import com.sunshine.orchestrator.conversation.dto.ConversationSummaryDto;
import com.sunshine.orchestrator.conversation.dto.MessagePageDto;
import com.sunshine.orchestrator.conversation.dto.UpdateCheckoutRequest;
import com.sunshine.orchestrator.conversation.dto.UpdateTitleRequest;
import com.sunshine.orchestrator.conversation.entity.ChatConversationEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @GetMapping("/conversations")
    public Mono<?> list(
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "workspaceId", required = false) String workspaceId,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        if (limit == null) {
            return ReactiveBlocking.call(() -> conversationService.list(userId, tenantId).stream()
                    .map(ConversationSummaryDto::from)
                    .toList());
        }
        return ReactiveBlocking.call(() ->
                conversationService.listPage(userId, tenantId, kind, workspaceId, offset, limit));
    }

    @PostMapping("/conversations")
    public Mono<ConversationSummaryDto> create(
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId,
            @RequestBody(required = false) java.util.Map<String, Object> body) {
        return ReactiveBlocking.call(() -> {
            String kind = body != null ? (String) body.getOrDefault("kind", "chat") : "chat";
            String workspaceId = body != null ? (String) body.get("workspaceId") : null;
            String checkoutPath = body != null ? (String) body.get("checkoutPath") : null;
            ChatConversationEntity conv = conversationService.create(userId, tenantId, kind, workspaceId, checkoutPath);
            return ConversationSummaryDto.from(conv);
        });
    }

    @GetMapping("/conversations/search")
    public Mono<List<ConversationSearchDto>> search(
            @RequestParam("q") String keyword,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> conversationService.search(userId, tenantId, keyword));
    }

    @GetMapping("/conversations/{id}")
    public Mono<ConversationDetailDto> get(
            @PathVariable("id") String id,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> {
            ChatConversationEntity conv = conversationService.getOwned(id, userId, tenantId);
            return ConversationDetailDto.from(conv, conversationService.getMessages(id, userId, tenantId));
        });
    }

    @GetMapping("/conversations/{id}/messages")
    public Mono<MessagePageDto> getMessagesPage(
            @PathVariable("id") String id,
            @RequestParam(value = "beforeSeq", defaultValue = "0") int beforeSeq,
            @RequestParam(value = "limit", defaultValue = "30") int limit,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() ->
                conversationService.getMessagesPage(id, userId, tenantId, beforeSeq, limit));
    }

    @PatchMapping("/conversations/{id}")
    public Mono<ConversationSummaryDto> updateTitle(
            @PathVariable("id") String id,
            @RequestBody UpdateTitleRequest body,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> {
            ChatConversationEntity conv = conversationService.updateTitle(
                    id, userId, tenantId, body.getTitle());
            return ConversationSummaryDto.from(conv);
        });
    }

    @PatchMapping("/conversations/{id}/checkout")
    public Mono<ConversationSummaryDto> updateCheckout(
            @PathVariable("id") String id,
            @RequestBody UpdateCheckoutRequest body,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> {
            ChatConversationEntity conv = conversationService.updateCheckoutPath(
                    id, userId, tenantId, body.getCheckoutPath());
            return ConversationSummaryDto.from(conv);
        });
    }

    @DeleteMapping("/conversations/{id}")
    public Mono<Void> delete(
            @PathVariable("id") String id,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.run(() -> conversationService.delete(id, userId, tenantId));
    }
}
