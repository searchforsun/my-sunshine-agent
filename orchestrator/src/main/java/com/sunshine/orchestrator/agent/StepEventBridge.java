package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * 按 assistant messageId 关联 Hook 与 TimelineSession（Hook 运行在 AgentScope 线程）。
 * Hook 产出的 step / step_delta 统一入队，由 {@link SunshineAgent} 与 Event 流按序 drain。
 */
public final class StepEventBridge {

    private static final Map<String, ProcessingTimelineSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, ConcurrentLinkedQueue<StreamToken>> HOOK_TOKEN_QUEUES = new ConcurrentHashMap<>();
    private static final Map<String, String> RAG_DETAILS = new ConcurrentHashMap<>();
    private static final Map<String, String> USER_QUERIES = new ConcurrentHashMap<>();

    private StepEventBridge() {
    }

    public static void bind(String messageId, ProcessingTimelineSession session) {
        bind(messageId, session, null);
    }

    public static void bind(String messageId, ProcessingTimelineSession session,
            ConcurrentLinkedQueue<StreamToken> hookTokenQueue) {
        if (messageId != null && session != null) {
            SESSIONS.put(messageId, session);
        }
        if (messageId != null && hookTokenQueue != null) {
            HOOK_TOKEN_QUEUES.put(messageId, hookTokenQueue);
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
            HOOK_TOKEN_QUEUES.remove(messageId);
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
            emitHookTokens(messageId, session, action);
        }
    }

    /**
     * Hook 无法拿到 messageId 时的兜底：仅当存在唯一活跃 session 时发射（单并发安全）
     */
    public static void emitSingleton(Consumer<ProcessingTimelineSession> action) {
        if (action == null || SESSIONS.size() != 1) {
            return;
        }
        SESSIONS.forEach((id, session) -> emitHookTokens(id, session, action));
    }

    /** ReasoningChunkEvent 原生增量 — 直接 step_delta，不经 Event REASONING 转换 */
    public static void emitSingletonReasoningChunk(String incrementalText) {
        if (incrementalText == null || incrementalText.isEmpty() || SESSIONS.size() != 1) {
            return;
        }
        SESSIONS.forEach((id, session) -> {
            String thinkId = session.currentThinkStepId();
            if (thinkId == null || !session.isThinkRunning()) {
                return;
            }
            ConcurrentLinkedQueue<StreamToken> queue = HOOK_TOKEN_QUEUES.get(id);
            if (queue != null) {
                queue.offer(StreamToken.stepDelta(thinkId, "reasoning", incrementalText));
            }
        });
    }

    private static void emitHookTokens(String messageId, ProcessingTimelineSession session,
            Consumer<ProcessingTimelineSession> action) {
        List<StreamToken> hookEmitted = ProcessingTimelineSupport.run(session, () -> action.accept(session));
        ConcurrentLinkedQueue<StreamToken> queue = HOOK_TOKEN_QUEUES.get(messageId);
        if (queue != null) {
            hookEmitted.forEach(queue::offer);
        }
    }
}
