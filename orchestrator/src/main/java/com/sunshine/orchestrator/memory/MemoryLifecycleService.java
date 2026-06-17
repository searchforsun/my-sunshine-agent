package com.sunshine.orchestrator.memory;

import com.sunshine.orchestrator.client.DesensitizeClient;
import com.sunshine.orchestrator.client.LlmGatewayClient;
import com.sunshine.orchestrator.conversation.ChatTurn;
import com.sunshine.orchestrator.conversation.ConversationService;
import com.sunshine.orchestrator.conversation.MessageStatus;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.memory.mtm.MtmSummarizeService;
import com.sunshine.orchestrator.memory.stm.StmStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 记忆生命周期 — 流式完成后刷新 STM、异步写入 MTM 摘要。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryLifecycleService {

    private final ConversationService conversationService;
    private final DesensitizeClient desensitizeClient;
    private final MtmSummarizeService mtmSummarizeService;

    @Autowired(required = false)
    private StmStore stmStore;

    public void onAssistantCompleted(
            String messageId, String userId, String tenantId, String status) {
        if (!MessageStatus.COMPLETED.equals(status)) {
            return;
        }
        try {
            ChatMessageEntity assistant = conversationService.getMessageOwned(messageId, userId, tenantId);
            String convId = assistant.getConversationId();
            List<ChatMessageEntity> messages = conversationService.getMessages(convId, userId, tenantId).stream()
                    .filter(m -> !MessageStatus.STREAMING.equals(m.getStatus()))
                    .toList();

            refreshStm(userId, convId, messages);
            mtmSummarizeService.summarizeIfNeeded(userId, tenantId, convId, assistant.getIntent(), messages);
        } catch (Exception e) {
            log.warn("[Memory] 生命周期处理失败 msg={}: {}", messageId, e.getMessage());
        }
    }

    private void refreshStm(String userId, String convId, List<ChatMessageEntity> messages) {
        if (stmStore == null || messages.isEmpty()) {
            return;
        }
        List<ChatTurn> turns = messages.stream()
                .filter(m -> "user".equals(m.getRole()) || "assistant".equals(m.getRole()))
                .map(m -> new ChatTurn(m.getRole(), m.getContent()))
                .collect(Collectors.toList());
        stmStore.replace(userId, convId, turns);
    }
}
