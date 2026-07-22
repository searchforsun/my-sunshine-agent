package com.sunshine.orchestrator.context.l3;

import com.sunshine.orchestrator.context.ContextProperties;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * L3 对话历史 ingest：completed 消息静默 upsert；失败仅日志。
 * 分块在 rag-service 侧用 FIXED 策略完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class L3IngestService {

    private final ContextProperties contextProperties;
    private final HistoryRagClient historyRagClient;

    @Async
    public void ingestAsync(
            String userId,
            String tenantId,
            String convId,
            String msgId,
            String content,
            long createdAtMs) {
        try {
            ingest(userId, tenantId, convId, msgId, content, createdAtMs);
        } catch (Exception e) {
            log.warn("[ContextL3] ingest 失败 msg={}: {}", msgId, e.getMessage());
        }
    }

    public void ingestAsync(String userId, String tenantId, ChatMessageEntity message) {
        if (message == null) {
            return;
        }
        long createdAt = message.getCreatedAt() != null
                ? message.getCreatedAt().toEpochMilli()
                : System.currentTimeMillis();
        ingestAsync(
                userId,
                tenantId,
                message.getConversationId(),
                message.getId(),
                message.getContent(),
                createdAt);
    }

    public void ingest(
            String userId,
            String tenantId,
            String convId,
            String msgId,
            String content,
            long createdAtMs) {
        if (!contextProperties.isEnabled()) {
            return;
        }
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(msgId) || !StringUtils.hasText(content)) {
            return;
        }
        historyRagClient.upsert(userId, tenantId, convId, msgId, content.strip(), createdAtMs).block();
    }
}
