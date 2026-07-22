package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.MessageStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 对话完成后的上下文写路径入口。
 * 委托 {@link ContextWritePath}：先 L2 抽取 → 再 L1 压缩（Far 以 L2 为准）→ L3 ingest。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextLifecycle {

    private final ContextWritePath contextWritePath;
    private final ContextProperties contextProperties;

    public void onTurnCompleted(String messageId, String userId, String tenantId, String status) {
        if (!MessageStatus.COMPLETED.equals(status)) {
            return;
        }
        if (!contextProperties.isEnabled()) {
            return;
        }
        contextWritePath.runAsync(messageId, userId, tenantId);
    }
}
