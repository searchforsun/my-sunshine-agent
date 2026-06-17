package com.sunshine.orchestrator.conversation;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 决定哪些历史消息注入 LLM：默认每轮独立，仅显式延续/指代时带上文。
 */
public final class ConversationContextPolicy {

    private static final Pattern CONTINUATION = Pattern.compile(
            "继续|补充|刚才|上面|上文|前面|之前|一起|接着|还有|同样|那个|这个|它|他们|她们|第二个|第一个|上一|前述");

    private ConversationContextPolicy() {
    }

    /** 用户是否在延续上一轮（含指代词） */
    public static boolean isContinuationRequest(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        return CONTINUATION.matcher(userMessage.strip()).find();
    }

    /**
     * 注入 LLM 的历史：非延续请求不带历史，避免把历史各轮任务合并为待办清单。
     *
     * @param loadedHistory 从 DB 加载的完整历史（不含本轮 user）
     * @param currentUser   本轮用户问题
     * @param maxWhenContinuing 延续模式下最多保留条数
     */
    public static List<ChatTurn> filterForLlm(
            List<ChatTurn> loadedHistory, String currentUser, int maxWhenContinuing) {
        if (loadedHistory == null || loadedHistory.isEmpty()) {
            return List.of();
        }
        if (!isContinuationRequest(currentUser)) {
            return List.of();
        }
        int limit = Math.max(2, maxWhenContinuing);
        if (loadedHistory.size() <= limit) {
            return List.copyOf(loadedHistory);
        }
        return List.copyOf(loadedHistory.subList(loadedHistory.size() - limit, loadedHistory.size()));
    }
}
