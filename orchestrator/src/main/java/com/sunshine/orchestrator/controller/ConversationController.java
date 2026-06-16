package com.sunshine.orchestrator.controller;

import com.sunshine.orchestrator.config.ReactiveBlocking;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.dto.ConversationDetailDto;
import com.sunshine.orchestrator.conversation.dto.ConversationSummaryDto;
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
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @GetMapping("/conversations")
    public Mono<List<ConversationSummaryDto>> list(
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> conversationService.list(userId, tenantId).stream()
                .map(ConversationSummaryDto::from)
                .toList());
    }

    @PostMapping("/conversations")
    public Mono<ConversationSummaryDto> create(
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.call(() -> {
            ChatConversationEntity conv = conversationService.create(userId, tenantId);
            return ConversationSummaryDto.from(conv);
        });
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

    @DeleteMapping("/conversations/{id}")
    public Mono<Void> delete(
            @PathVariable("id") String id,
            @RequestHeader("x-user-id") String userId,
            @RequestHeader(value = "x-tenant-id", defaultValue = "default") String tenantId) {
        return ReactiveBlocking.run(() -> conversationService.delete(id, userId, tenantId));
    }
}
