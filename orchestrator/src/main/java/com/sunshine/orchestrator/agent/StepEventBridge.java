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
    /** bridgeId → 主会话 assistantMessageId（子 Agent / 多 bridge：timeline 用 bridgeId，SSE generation 用 assistantMessageId） */
    private static final Map<String, String> HITL_ASSISTANT_BY_BRIDGE = new ConcurrentHashMap<>();
    /** assistantMessageId → 当前活跃 MAIN run bridge（续跑时作废旧 run，防双 Agent 并发刷 tool） */
    private static final Map<String, String> MAIN_RUN_BY_MESSAGE = new ConcurrentHashMap<>();
    /** 注册 MAIN ReAct run；续跑/register 新 run 时清理上一 run 的 bridge 状态 */
    public static void registerMainRun(String assistantMessageId, String bridgeId) {
        if (assistantMessageId == null || assistantMessageId.isBlank() || bridgeId == null || bridgeId.isBlank()) {
            return;
        }
        String msg = assistantMessageId.strip();
        String bridge = bridgeId.strip();
        String prev = MAIN_RUN_BY_MESSAGE.put(msg, bridge);
        if (prev != null && !prev.equals(bridge)) {
            clear(prev);
        }
    }

    public static void unregisterMainRun(String assistantMessageId, String bridgeId) {
        if (assistantMessageId == null || assistantMessageId.isBlank() || bridgeId == null || bridgeId.isBlank()) {
            return;
        }
        MAIN_RUN_BY_MESSAGE.remove(assistantMessageId.strip(), bridgeId.strip());
    }

    public static boolean isActiveMainBridge(String assistantMessageId, String bridgeId) {
        if (assistantMessageId == null || bridgeId == null) {
            return false;
        }
        return bridgeId.strip().equals(MAIN_RUN_BY_MESSAGE.get(assistantMessageId.strip()));
    }

    public static String activeMainBridge(String assistantMessageId) {
        if (assistantMessageId == null || assistantMessageId.isBlank()) {
            return null;
        }
        return MAIN_RUN_BY_MESSAGE.get(assistantMessageId.strip());
    }

    /** AgentScope toolUseId → bridgeId（工具在 boundedElastic 线程执行，不能靠 SESSIONS.size==1） */
    private static final Map<String, String> TOOL_USE_BRIDGE = new ConcurrentHashMap<>();
    /** ReAct 续跑 re-await 已确认：同轮工具调用跳过一次 HITL（messageId → toolId|params） */
    private static final Map<String, String> HITL_PREAPPROVED = new ConcurrentHashMap<>();
    /** 子 Agent：Hook token 刷 SSE 前经 bridge.wrap 折叠进 node.subSteps */
    private static final Map<String, Function<StreamToken, List<StreamToken>>> TOKEN_WRAPPERS = new ConcurrentHashMap<>();
    /** Redis GenerationJob 路径：Hook 产出后立即刷 SSE，不等 agent.stream AGENT_RESULT */
    private static final Map<String, FlushBinding> GENERATION_FLUSH = new ConcurrentHashMap<>();
    /** 续跑/取消后递增，旧 Agent 线程的 Hook 不得再写入新 generation */
    private static final Map<String, Long> STREAM_EPOCH = new ConcurrentHashMap<>();
    /** bind 时捕获的 epoch，与 GENERATION_FLUSH.epoch 对齐才刷 SSE */
    private static final Map<String, Long> SESSION_STREAM_EPOCH = new ConcurrentHashMap<>();

    private record FlushBinding(long epoch, Consumer<StreamToken> consumer) {
    }

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
            SESSION_STREAM_EPOCH.put(messageId, currentStreamEpoch(messageId));
        }
        if (messageId != null && hookTokenQueue != null) {
            HOOK_TOKEN_QUEUES.put(messageId, hookTokenQueue);
        }
    }

    public static long currentStreamEpoch(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return 0L;
        }
        return STREAM_EPOCH.getOrDefault(messageId.strip(), 0L);
    }

    /** 取消/续跑重规划：使旧 Agent 线程的 Hook 与 flush 绑定失效 */
    public static long bumpStreamEpoch(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return 0L;
        }
        String key = messageId.strip();
        long next = STREAM_EPOCH.merge(key, 0L, (k, v) -> v + 1);
        GENERATION_FLUSH.remove(key);
        return next;
    }

    public static boolean isStreamEpochValid(String messageId, long epoch) {
        return messageId != null && !messageId.isBlank() && epoch == currentStreamEpoch(messageId.strip());
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
        bindGenerationFlush(messageId, currentStreamEpoch(messageId), consumer);
    }

    public static void bindGenerationFlush(String messageId, long epoch, Consumer<StreamToken> consumer) {
        if (messageId != null && consumer != null) {
            GENERATION_FLUSH.put(messageId, new FlushBinding(epoch, consumer));
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

    /** ReAct 单会话时返回 assistantMessageId，供 RagTool 等 Hook 侧组件关联 trace */
    public static String activeMessageId() {
        String bridge = activeBridgeId();
        if (bridge == null) {
            return null;
        }
        String mapped = hitlAssistantMessageId(bridge);
        return mapped != null ? mapped : bridge;
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
            HITL_PREAPPROVED.remove(messageId);
            TOKEN_WRAPPERS.remove(messageId);
            GENERATION_FLUSH.remove(messageId);
            SESSION_STREAM_EPOCH.remove(messageId);
            TOOL_USE_BRIDGE.entrySet().removeIf(e -> messageId.equals(e.getValue()));
        }
    }

    /** ReAct 续跑重规划：作废该 assistant 下全部 MAIN run bridge，避免旧 HITL 线程刷重复 tool */
    public static void clearForReactRestart(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        String msg = messageId.strip();
        String activeBridge = MAIN_RUN_BY_MESSAGE.remove(msg);
        if (activeBridge != null) {
            clear(activeBridge);
        }
        for (String bridgeId : java.util.List.copyOf(HITL_ASSISTANT_BY_BRIDGE.keySet())) {
            if (msg.equals(HITL_ASSISTANT_BY_BRIDGE.get(bridgeId))) {
                clear(bridgeId);
            }
        }
        clear(msg);
    }

    /** Hook 回调 — 按 messageId 精确路由 */
    public static void emit(String messageId, Consumer<ProcessingTimelineSession> action) {
        if (messageId == null || action == null || !isHookBridgeActive(messageId)) {
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

    /** ReasoningChunk TextBlock 正文增量 — 即时写入 GenerationJob（真流式） */
    public static void emitReasoningContentChunk(String messageId, String incrementalText) {
        if (messageId == null || incrementalText == null || incrementalText.isEmpty()) {
            return;
        }
        ProcessingTimelineSession session = SESSIONS.get(messageId);
        if (session == null) {
            return;
        }
        List<StreamToken> emitted = ProcessingTimelineSupport.run(
                session, () -> session.ingestStreamingContentDelta(incrementalText));
        ConcurrentLinkedQueue<StreamToken> queue = HOOK_TOKEN_QUEUES.get(messageId);
        if (queue != null || GENERATION_FLUSH.containsKey(messageId)) {
            emitted.forEach(token -> routeHookToken(messageId, token, queue));
        }
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
        if (!isHookBridgeActive(messageId)) {
            return;
        }
        String flushKey = resolveFlushMessageId(messageId);
        FlushBinding binding = flushKey != null ? GENERATION_FLUSH.get(flushKey) : null;
        if (binding != null && isHookFlushAllowed(messageId, flushKey, binding.epoch())) {
            Consumer<StreamToken> sink = binding.consumer();
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

    /** MAIN run 续跑后旧 bridge 不得再刷 Hook / SSE */
    public static boolean isHookBridgeActive(String bridgeId) {
        if (bridgeId == null || bridgeId.isBlank()) {
            return false;
        }
        if (!bridgeId.startsWith("main-")) {
            return true;
        }
        String assistantId = hitlAssistantMessageId(bridgeId);
        if (assistantId == null) {
            return true;
        }
        return isActiveMainBridge(assistantId, bridgeId);
    }

    private static String resolveFlushMessageId(String bridgeId) {
        if (bridgeId == null || bridgeId.isBlank()) {
            return null;
        }
        String assistantId = hitlAssistantMessageId(bridgeId);
        return assistantId != null ? assistantId : bridgeId;
    }

    private static boolean isHookFlushAllowed(String bridgeId, String flushKey, long bindingEpoch) {
        Long sessionEpoch = SESSION_STREAM_EPOCH.get(bridgeId);
        if (sessionEpoch == null || sessionEpoch != bindingEpoch) {
            return false;
        }
        return bindingEpoch == currentStreamEpoch(flushKey);
    }

    /** 将 Hook 队列中的 step / step_delta 刷入 GenerationJob（避免 HITL confirmation 抢先于 think / tool 步骤） */
    public static void drainHookQueueToGeneration(String messageId,
            java.util.function.Consumer<StreamToken> tokenConsumer) {
        if (messageId == null || tokenConsumer == null || !isHookBridgeActive(messageId)) {
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
