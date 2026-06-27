package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 按 assistant messageId 关联 Hook 与 TimelineSession（Hook 运行在 AgentScope 线程）。
 * Hook 产出的 step / step_delta 统一入队，由 {@link com.sunshine.orchestrator.agent.runtime.ReActAgentRuntime} 与 Event 流按序 drain。
 */
public final class StepEventBridge {

    private static final Map<String, ProcessingTimelineSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, ConcurrentLinkedQueue<StreamToken>> HOOK_TOKEN_QUEUES = new ConcurrentHashMap<>();
    private static final Map<String, String> RAG_DETAILS = new ConcurrentHashMap<>();
    private static final Map<String, String> USER_QUERIES = new ConcurrentHashMap<>();
    private static final Map<String, ToolAuditContext> TOOL_AUDIT_CONTEXTS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> HITL_ENABLED = new ConcurrentHashMap<>();
    /** bridgeId → 主会话 assistantMessageId（子 Agent / workflow 写 SSE 用） */
    private static final Map<String, String> HITL_ASSISTANT_BY_BRIDGE = new ConcurrentHashMap<>();
    /** AgentScope toolUseId → bridgeId（工具在 boundedElastic 线程执行，不能靠 SESSIONS.size==1） */
    private static final Map<String, String> TOOL_USE_BRIDGE = new ConcurrentHashMap<>();
    /** ReAct 续跑 re-await 已确认：同轮工具调用跳过一次 HITL（messageId → toolId|params） */
    private static final Map<String, String> HITL_PREAPPROVED = new ConcurrentHashMap<>();
    /** 子 Agent：Hook token 刷 SSE 前经 bridge.wrap 折叠进 node.subSteps */
    private static final Map<String, Function<StreamToken, List<StreamToken>>> TOKEN_WRAPPERS = new ConcurrentHashMap<>();
    /** Redis GenerationJob 路径：Hook 产出后立即刷 SSE，不等 agent.stream AGENT_RESULT */
    private static final Map<String, Consumer<StreamToken>> GENERATION_FLUSH = new ConcurrentHashMap<>();

    /** ReAct / workflow 工具审计上下文 — 按 assistantMsgId 绑定 */
    public record ToolAuditContext(
            String conversationId,
            String messageId,
            String userId,
            String tenantId,
            String planId) {
    }

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

    public static void bindToolAudit(String messageId, ToolAuditContext context) {
        if (messageId != null && context != null) {
            TOOL_AUDIT_CONTEXTS.put(messageId, context);
        }
    }

    /** MAIN ReAct：bridgeId 与 assistantMessageId 相同 */
    public static void bindHitl(String messageId, boolean enabled) {
        bindHitlBridge(messageId, messageId, enabled);
    }

    /** 子 Agent / 多 bridge：timeline 用 bridgeId，SSE generation 用 assistantMessageId */
    public static void bindHitlBridge(String bridgeId, String assistantMessageId, boolean enabled) {
        if (bridgeId == null) {
            return;
        }
        if (enabled) {
            HITL_ENABLED.put(bridgeId, true);
            if (assistantMessageId != null && !assistantMessageId.isBlank()) {
                HITL_ASSISTANT_BY_BRIDGE.put(bridgeId, assistantMessageId.strip());
            }
        } else {
            HITL_ENABLED.remove(bridgeId);
            HITL_ASSISTANT_BY_BRIDGE.remove(bridgeId);
        }
    }

    /** 子 Agent 执行前注册，使 HITL flush 与主 Timeline 隔离 */
    public static void bindTokenWrapper(String bridgeId, Function<StreamToken, List<StreamToken>> wrapper) {
        if (bridgeId != null && wrapper != null) {
            TOKEN_WRAPPERS.put(bridgeId, wrapper);
        }
    }

    /** GenerationJob 注册后绑定：Hook step / step_delta 即时写入 Redis 流 */
    public static void bindGenerationFlush(String messageId, Consumer<StreamToken> consumer) {
        if (messageId != null && consumer != null) {
            GENERATION_FLUSH.put(messageId, consumer);
        }
    }

    public static void unbindGenerationFlush(String messageId) {
        if (messageId != null) {
            GENERATION_FLUSH.remove(messageId);
        }
    }

    public static boolean hitlEnabled() {
        return hitlEnabledForBridge(resolveHitlBridgeId());
    }

    public static boolean hitlEnabledForBridge(String bridgeId) {
        return bridgeId != null && Boolean.TRUE.equals(HITL_ENABLED.get(bridgeId));
    }

    /** ReAct 续跑 re-await 通过后，同 message 下一次同参写工具免二次确认 */
    public static void grantHitlPreApproval(String messageId, String toolId, Map<String, String> params) {
        if (messageId == null || messageId.isBlank() || toolId == null || toolId.isBlank()) {
            return;
        }
        HITL_PREAPPROVED.put(messageId.strip(), hitlPreApprovalKey(toolId.strip(), params));
    }

    public static boolean consumeHitlPreApproval(String messageId, String toolId, Map<String, String> params) {
        if (messageId == null || messageId.isBlank() || toolId == null || toolId.isBlank()) {
            return false;
        }
        String expected = hitlPreApprovalKey(toolId.strip(), params);
        return expected.equals(HITL_PREAPPROVED.remove(messageId.strip()));
    }

    private static String hitlPreApprovalKey(String toolId, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return toolId;
        }
        String summary = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
        return toolId + "|" + summary;
    }

    /** PreActing 注册；PostActing 须 unbind */
    public static void bindToolUseBridge(String toolUseId, String bridgeId) {
        if (toolUseId != null && !toolUseId.isBlank() && bridgeId != null && !bridgeId.isBlank()) {
            TOOL_USE_BRIDGE.put(toolUseId.strip(), bridgeId.strip());
        }
    }

    public static void unbindToolUseBridge(String toolUseId) {
        if (toolUseId != null && !toolUseId.isBlank()) {
            TOOL_USE_BRIDGE.remove(toolUseId.strip());
        }
    }

    public static String bridgeIdForToolUse(String toolUseId) {
        if (toolUseId == null || toolUseId.isBlank()) {
            return null;
        }
        return TOOL_USE_BRIDGE.get(toolUseId.strip());
    }

    /** 单会话优先；多并发时回退到唯一 HITL bridge 或 toolUse 映射 */
    public static String resolveHitlBridgeId() {
        if (TOOL_USE_BRIDGE.size() == 1) {
            String fromToolUse = TOOL_USE_BRIDGE.values().iterator().next();
            if (hitlEnabledForBridge(fromToolUse)) {
                return fromToolUse;
            }
        }
        if (SESSIONS.size() == 1) {
            String id = SESSIONS.keySet().iterator().next();
            if (hitlEnabledForBridge(id)) {
                return id;
            }
        }
        if (HITL_ENABLED.size() == 1) {
            return HITL_ENABLED.keySet().iterator().next();
        }
        return null;
    }

    public static String activeBridgeId() {
        if (SESSIONS.size() != 1) {
            return null;
        }
        return SESSIONS.keySet().iterator().next();
    }

    public static String hitlAssistantMessageId(String bridgeId) {
        if (bridgeId == null || bridgeId.isBlank()) {
            return null;
        }
        String mapped = HITL_ASSISTANT_BY_BRIDGE.get(bridgeId);
        if (mapped != null && !mapped.isBlank()) {
            return mapped;
        }
        return Boolean.TRUE.equals(HITL_ENABLED.get(bridgeId)) ? bridgeId : null;
    }

    public static ToolAuditContext toolAuditContext(String messageId) {
        return messageId == null ? null : TOOL_AUDIT_CONTEXTS.get(messageId);
    }

    public static String ragDetail(String messageId) {
        return messageId == null ? null : RAG_DETAILS.get(messageId);
    }

    public static String userQuery(String messageId) {
        return messageId == null ? null : USER_QUERIES.get(messageId);
    }

    /** ReAct 单会话时返回当前 bridge messageId，供 RagTool 等 Hook 侧组件关联 trace */
    public static String activeMessageId() {
        return activeBridgeId();
    }

    public static void clear(String messageId) {
        if (messageId != null) {
            SESSIONS.remove(messageId);
            HOOK_TOKEN_QUEUES.remove(messageId);
            RAG_DETAILS.remove(messageId);
            USER_QUERIES.remove(messageId);
            TOOL_AUDIT_CONTEXTS.remove(messageId);
            HITL_ENABLED.remove(messageId);
            HITL_ASSISTANT_BY_BRIDGE.remove(messageId);
            TOKEN_WRAPPERS.remove(messageId);
            GENERATION_FLUSH.remove(messageId);
            TOOL_USE_BRIDGE.entrySet().removeIf(e -> messageId.equals(e.getValue()));
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

    /** ReasoningChunkEvent 原生增量 — 按 messageId 精确路由 */
    public static void emitReasoningChunk(String messageId, String incrementalText) {
        if (messageId == null || incrementalText == null || incrementalText.isEmpty()) {
            return;
        }
        ProcessingTimelineSession session = SESSIONS.get(messageId);
        if (session == null) {
            return;
        }
        String thinkId = session.currentThinkStepId();
        if (thinkId == null || !session.isThinkRunning()) {
            return;
        }
        ConcurrentLinkedQueue<StreamToken> queue = HOOK_TOKEN_QUEUES.get(messageId);
        if (queue != null) {
            routeHookToken(messageId, StreamToken.stepDelta(thinkId, "reasoning", incrementalText), queue);
        }
    }

    /** Hook 无法拿到 messageId 时的兜底：仅当存在唯一活跃 session 时发射（单并发安全） */
    public static void emitSingletonReasoningChunk(String incrementalText) {
        if (incrementalText == null || incrementalText.isEmpty() || SESSIONS.size() != 1) {
            return;
        }
        SESSIONS.forEach((id, session) -> emitReasoningChunk(id, incrementalText));
    }

    private static void emitHookTokens(String messageId, ProcessingTimelineSession session,
            Consumer<ProcessingTimelineSession> action) {
        List<StreamToken> hookEmitted = ProcessingTimelineSupport.run(session, () -> action.accept(session));
        ConcurrentLinkedQueue<StreamToken> queue = HOOK_TOKEN_QUEUES.get(messageId);
        if (queue != null || GENERATION_FLUSH.containsKey(messageId)) {
            hookEmitted.forEach(token -> routeHookToken(messageId, token, queue));
        }
    }

    private static void routeHookToken(String messageId, StreamToken token,
            ConcurrentLinkedQueue<StreamToken> queue) {
        Consumer<StreamToken> sink = GENERATION_FLUSH.get(messageId);
        if (sink != null) {
            Function<StreamToken, List<StreamToken>> wrapper = TOKEN_WRAPPERS.get(messageId);
            if (wrapper != null) {
                List<StreamToken> wrapped = wrapper.apply(token);
                if (wrapped != null) {
                    wrapped.forEach(sink);
                }
            } else {
                sink.accept(token);
            }
            return;
        }
        if (queue != null) {
            queue.offer(token);
        }
    }

    /** 将 Hook 队列中的 step / step_delta 刷入 GenerationJob（避免 HITL confirmation 抢先于 think / tool 步骤） */
    public static void drainHookQueueToGeneration(String messageId,
            java.util.function.Consumer<StreamToken> tokenConsumer) {
        if (messageId == null || tokenConsumer == null) {
            return;
        }
        ConcurrentLinkedQueue<StreamToken> queue = HOOK_TOKEN_QUEUES.get(messageId);
        if (queue == null) {
            return;
        }
        StreamToken token;
        Function<StreamToken, List<StreamToken>> wrapper = TOKEN_WRAPPERS.get(messageId);
        while ((token = queue.poll()) != null) {
            if (wrapper != null) {
                List<StreamToken> wrapped = wrapper.apply(token);
                if (wrapped != null) {
                    wrapped.forEach(tokenConsumer);
                }
            } else {
                tokenConsumer.accept(token);
            }
        }
    }
}
