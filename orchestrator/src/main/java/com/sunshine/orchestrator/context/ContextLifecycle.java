package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.MessageBodyText;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.context.l1.L1Compressor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 对话完成后的上下文写路径入口（替代 MemoryLifecycleService）。
 * 本 Task：L1 Mid/Far 压缩；后续 Task 挂 L2 / L3。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextLifecycle {

    private final ConversationService conversationService;
    private final L1Compressor l1Compressor;
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
            List<SessionTurn> history = conversationService.getMessages(convId, userId, tenantId).stream()
                    .filter(m -> !MessageStatus.STREAMING.equals(m.getStatus()))
                    .filter(m -> "user".equals(m.getRole()) || "assistant".equals(m.getRole()))
                    .map(m -> SessionTurn.of(m.getId(), m.getRole(), MessageBodyText.resolve(m)))
                    .filter(t -> StringUtils.hasText(t.content()))
                    .toList();
            l1Compressor.compressAsync(userId, tenantId, convId, history);
        } catch (Exception e) {
            log.warn("[Context] onTurnCompleted 失败 msg={}: {}", messageId, e.getMessage());
        }
    }
}
