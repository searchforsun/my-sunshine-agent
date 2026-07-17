package com.sunshine.orchestrator.conversation;

import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import com.sunshine.orchestrator.processing.ContentBlocksJson;

/**
 * 消息正文解析 — STM/MTM/历史注入统一入口。
 */
public final class MessageBodyText {

    private MessageBodyText() {
    }

    public static String resolve(ChatMessageEntity message) {
        if (message == null) {
            return "";
        }
        return ContentBlocksJson.resolveBody(message.getContent(), message.getContentBlocks());
    }
}
