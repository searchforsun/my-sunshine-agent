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
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 对话完成后的上下文写路径入口（替代 MemoryLifecycleService）。
 * L1 Mid/Far 压缩 → L2 静默抽取 → L3 chunk ingest。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextLifecycle {

    private final ConversationService conversationService;
    private final L1Compressor l1Compressor;
    private final L2ExtractService l2ExtractService;
    private final L3IngestService l3IngestService;
    private final ContextProperties contextProperties;

    public void onTurnCompleted(String messageId, String userId, String tenantId, String status) {
        if (!MessageStatus.COMPLETED.equals(status)) {
            return;
        }
        if (!contextProperties.isEnabled()) {
            return;
        }
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
            l1Compressor.compressAsync(userId, tenantId, convId, history);
            l2ExtractService.extractAsync(userId, tenantId, messageId, history);
            // 本轮 user + assistant 静默 ingest（失败不阻断）
            ingestTurnPair(userId, tenantId, messages, assistant);
        } catch (Exception e) {
            log.warn("[Context] onTurnCompleted 失败 msg={}: {}", messageId, e.getMessage());
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
