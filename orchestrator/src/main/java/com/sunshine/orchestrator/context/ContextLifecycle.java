package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.MessageStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 对话终态后的上下文写路径入口。
 * 委托 {@link ContextWritePath}：COMPLETED 走全量（L2 抽取 → L1 压缩 → L3 ingest）；
 * INTERRUPTED/FAILED 仅补 L1 折叠——压缩点同步推进退役的「间隙轮」（P\S，已退役未折叠）
 * 不依赖本轮成功：若只挂 COMPLETED，中断后间隙轮滞留且对模型不可见，续跑时上下文缺失。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextLifecycle {

    private final ContextWritePath contextWritePath;
    private final ContextProperties contextProperties;

    public void onTurnCompleted(String messageId, String userId, String tenantId, String status) {
        if (!contextProperties.isEnabled()) {
            return;
        }
        if (MessageStatus.COMPLETED.equals(status)) {
            contextWritePath.runAsync(messageId, userId, tenantId);
            return;
        }
        if (MessageStatus.isResumable(status)) {
            contextWritePath.runFoldOnlyAsync(messageId, userId, tenantId);
        }
    }
}
