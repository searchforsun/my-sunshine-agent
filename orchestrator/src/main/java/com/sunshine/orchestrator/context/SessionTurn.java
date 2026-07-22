package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;

/**
 * 上下文组装用轮次：可选 messageId，供 Mid {@code mid_answers} 查找。
 */
public record SessionTurn(String messageId, String role, String content) {

    public static SessionTurn of(String role, String content) {
        return new SessionTurn(null, role, content);
    }

    public static SessionTurn of(String messageId, String role, String content) {
        return new SessionTurn(messageId, role, content);
    }

    public ChatTurn toChatTurn() {
        return new ChatTurn(role, content);
    }

    public SessionTurn withContent(String newContent) {
        return new SessionTurn(messageId, role, newContent);
    }
}
