package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.MessageBodyText;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.context.l1.L1Compressor;
import com.sunshine.orchestrator.context.l2.L2ExtractService;
import com.sunshine.orchestrator.context.l3.L3IngestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 对话完成后的上下文写路径：先 L2 抽取，再 L1 压缩（Far 可读本轮 L2），再 L3 ingest。
 * 独立 Bean + {@code @Async}，避免 Lifecycle 自调用导致异步失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextWritePath {

    private final ConversationService conversationService;
    private final L1Compressor l1Compressor;
    private final L2ExtractService l2ExtractService;
    private final L3IngestService l3IngestService;

    @Async
    public void runAsync(String messageId, String userId, String tenantId) {
        try {
            ChatMessageEntity assistant = conversationService.getMessageOwned(messageId, userId, tenantId);
            String convId = assistant.getConversationId();
            List<ChatMessageEntity> messages = conversationService.getMessages(convId, userId, tenantId);
            List<SessionTurn> history = messages.stream()
                    .filter(m -> !MessageStatus.STREAMING.equals(m.getStatus()))
                    .filter(m -> "user".equals(m.getRole()) || "assistant".equals(m.getRole()))
                    .map(m -> SessionTurn.of(m.getId(), m.getRole(), MessageBodyText.resolve(m)))
                    .filter(t -> StringUtils.hasText(t.content()))
                    .toList();
            l2ExtractService.extract(userId, tenantId, messageId, history);
            l1Compressor.compress(userId, tenantId, convId, history);
            ingestTurnPair(userId, tenantId, messages, assistant);
        } catch (Exception e) {
            log.warn("[Context] writePath 失败 msg={}: {}", messageId, e.getMessage());
        }
    }

    private void ingestTurnPair(
            String userId,
            String tenantId,
            List<ChatMessageEntity> messages,
            ChatMessageEntity assistant) {
        ChatMessageEntity precedingUser = findPrecedingUser(messages, assistant);
        if (precedingUser != null) {
            String body = MessageBodyText.resolve(precedingUser);
            if (StringUtils.hasText(body)) {
                long createdAt = precedingUser.getCreatedAt() != null
                        ? precedingUser.getCreatedAt().toEpochMilli()
                        : System.currentTimeMillis();
                l3IngestService.ingestAsync(
                        userId, tenantId, precedingUser.getConversationId(),
                        precedingUser.getId(), body, createdAt);
            }
        }
        String assistantBody = MessageBodyText.resolve(assistant);
        if (StringUtils.hasText(assistantBody)) {
            long createdAt = assistant.getCreatedAt() != null
                    ? assistant.getCreatedAt().toEpochMilli()
                    : System.currentTimeMillis();
            l3IngestService.ingestAsync(
                    userId, tenantId, assistant.getConversationId(),
                    assistant.getId(), assistantBody, createdAt);
        }
    }

    private static ChatMessageEntity findPrecedingUser(
            List<ChatMessageEntity> messages, ChatMessageEntity assistant) {
        if (messages == null || assistant == null) {
            return null;
        }
        ChatMessageEntity prev = null;
        for (ChatMessageEntity m : messages) {
            if (m == null) {
                continue;
            }
            if (assistant.getId() != null && assistant.getId().equals(m.getId())) {
                return prev != null && "user".equals(prev.getRole()) ? prev : null;
            }
            if ("user".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                prev = m;
            }
        }
        return null;
    }
}
