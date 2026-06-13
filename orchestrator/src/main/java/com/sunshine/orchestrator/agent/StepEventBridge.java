package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.processing.ProcessingTimelineSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 按 assistant messageId 关联 Hook 与 TimelineSession（Hook 运行在 AgentScope 线程）
 */
public final class StepEventBridge {

    private static final Map<String, ProcessingTimelineSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, String> RAG_DETAILS = new ConcurrentHashMap<>();
    private static final Map<String, String> USER_QUERIES = new ConcurrentHashMap<>();

    private StepEventBridge() {
    }

    public static void bind(String messageId, ProcessingTimelineSession session) {
        if (messageId != null && session != null) {
            SESSIONS.put(messageId, session);
        }
    }

    public static void setRagDetail(String messageId, String detail) {
        if (messageId != null && detail != null) {
            RAG_DETAILS.put(messageId, detail);
        }
    }

    public static void setUserQuery(String messageId, String query) {
        if (messageId != null && query != null && !query.isBlank()) {
            USER_QUERIES.put(messageId, query.strip());
        }
    }

    public static String ragDetail(String messageId) {
        return messageId == null ? null : RAG_DETAILS.get(messageId);
    }

    public static String userQuery(String messageId) {
        return messageId == null ? null : USER_QUERIES.get(messageId);
    }

    public static void clear(String messageId) {
        if (messageId != null) {
            SESSIONS.remove(messageId);
            RAG_DETAILS.remove(messageId);
            USER_QUERIES.remove(messageId);
        }
    }

    /** Hook 回调 — 按 messageId 精确路由 */
    public static void emit(String messageId, Consumer<ProcessingTimelineSession> action) {
        if (messageId == null || action == null) {
            return;
        }
        ProcessingTimelineSession session = SESSIONS.get(messageId);
        if (session != null) {
            action.accept(session);
        }
    }

    /**
     * Hook 无法拿到 messageId 时的兜底：仅当存在唯一活跃 session 时发射（单并发安全）
     */
    public static void emitSingleton(Consumer<ProcessingTimelineSession> action) {
        if (action == null || SESSIONS.size() != 1) {
            return;
        }
        SESSIONS.values().forEach(action);
    }
}
