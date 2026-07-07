package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * per-bridge Hook / Timeline / Generation 绑定注册表 — 替代 StepEventBridge 静态 Map。
 * 生产由 Spring 单例托管；单测可走 {@link StepEventBridge} 静态门面或独立实例。
 */
@Component
public class StepEventBridgeRegistry {

    private final Map<String, ProcessingTimelineSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<StreamToken>> hookTokenQueues = new ConcurrentHashMap<>();
    private final Map<String, String> ragDetails = new ConcurrentHashMap<>();
    private final Map<String, String> userQueries = new ConcurrentHashMap<>();
    private final Map<String, StepEventBridge.ToolAuditContext> toolAuditContexts = new ConcurrentHashMap<>();
    private final Map<String, Boolean> hitlEnabled = new ConcurrentHashMap<>();
    private final Map<String, String> hitlAssistantByBridge = new ConcurrentHashMap<>();
    private final Map<String, String> mainRunByMessage = new ConcurrentHashMap<>();
    private final Map<String, String> toolUseBridge = new ConcurrentHashMap<>();
    private final Map<String, String> hitlPreapproved = new ConcurrentHashMap<>();
    private final Map<String, Function<StreamToken, List<StreamToken>>> tokenWrappers = new ConcurrentHashMap<>();
    private final Map<String, FlushBinding> generationFlush = new ConcurrentHashMap<>();
    private final Map<String, Long> streamEpoch = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionStreamEpoch = new ConcurrentHashMap<>();

    @PostConstruct
    void installFacade() {
        StepEventBridge.bindRegistry(this);
    }

    private record FlushBinding(long epoch, Consumer<StreamToken> consumer) {
    }

    /** 单测隔离：清空全部绑定（生产勿调用） */
    public void clearAll() {
        sessions.clear();
        hookTokenQueues.clear();
        ragDetails.clear();
        userQueries.clear();
        toolAuditContexts.clear();
        hitlEnabled.clear();
        hitlAssistantByBridge.clear();
        mainRunByMessage.clear();
        toolUseBridge.clear();
        hitlPreapproved.clear();
        tokenWrappers.clear();
        generationFlush.clear();
        streamEpoch.clear();
        sessionStreamEpoch.clear();
    }

    public void registerMainRun(String assistantMessageId, String bridgeId) {
        if (assistantMessageId == null || assistantMessageId.isBlank() || bridgeId == null || bridgeId.isBlank()) {
            return;
        }
        String msg = assistantMessageId.strip();
        String bridge = bridgeId.strip();
        String prev = mainRunByMessage.put(msg, bridge);
        if (prev != null && !prev.equals(bridge)) {
            clear(prev);
        }
    }

    public void unregisterMainRun(String assistantMessageId, String bridgeId) {
        if (assistantMessageId == null || assistantMessageId.isBlank() || bridgeId == null || bridgeId.isBlank()) {
            return;
        }
        mainRunByMessage.remove(assistantMessageId.strip(), bridgeId.strip());
    }

    public boolean isActiveMainBridge(String assistantMessageId, String bridgeId) {
        if (assistantMessageId == null || bridgeId == null) {
            return false;
        }
        return bridgeId.strip().equals(mainRunByMessage.get(assistantMessageId.strip()));
    }

    public String activeMainBridge(String assistantMessageId) {
        if (assistantMessageId == null || assistantMessageId.isBlank()) {
            return null;
        }
        return mainRunByMessage.get(assistantMessageId.strip());
    }

    public void bind(String messageId, ProcessingTimelineSession session) {
        bind(messageId, session, null);
    }

    public void bind(String messageId, ProcessingTimelineSession session,
            ConcurrentLinkedQueue<StreamToken> hookTokenQueue) {
        if (messageId != null && session != null) {
            sessions.put(messageId, session);
            sessionStreamEpoch.put(messageId, currentStreamEpoch(messageId));
        }
        if (messageId != null && hookTokenQueue != null) {
            hookTokenQueues.put(messageId, hookTokenQueue);
        }
    }

    public long currentStreamEpoch(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return 0L;
        }
        return streamEpoch.getOrDefault(messageId.strip(), 0L);
    }

    public long bumpStreamEpoch(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return 0L;
        }
        String key = messageId.strip();
        long next = streamEpoch.merge(key, 0L, (k, v) -> v + 1);
        generationFlush.remove(key);
        return next;
    }

    public boolean isStreamEpochValid(String messageId, long epoch) {
        return messageId != null && !messageId.isBlank() && epoch == currentStreamEpoch(messageId.strip());
    }

    public void setRagDetail(String messageId, String detail) {
        if (messageId != null && detail != null) {
            ragDetails.put(messageId, detail);
        }
    }

    public void setUserQuery(String messageId, String query) {
        if (messageId != null && query != null && !query.isBlank()) {
            userQueries.put(messageId, query.strip());
        }
    }

    public void bindToolAudit(String messageId, StepEventBridge.ToolAuditContext context) {
        if (messageId != null && context != null) {
            toolAuditContexts.put(messageId, context);
        }
    }

    public void bindHitl(String messageId, boolean enabled) {
        bindHitlBridge(messageId, messageId, enabled);
    }

    public void bindHitlBridge(String bridgeId, String assistantMessageId, boolean enabled) {
        if (bridgeId == null) {
            return;
        }
        if (enabled) {
            hitlEnabled.put(bridgeId, true);
            if (assistantMessageId != null && !assistantMessageId.isBlank()) {
                hitlAssistantByBridge.put(bridgeId, assistantMessageId.strip());
            }
        } else {
            hitlEnabled.remove(bridgeId);
            hitlAssistantByBridge.remove(bridgeId);
        }
    }

    public void bindTokenWrapper(String bridgeId, Function<StreamToken, List<StreamToken>> wrapper) {
        if (bridgeId != null && wrapper != null) {
            tokenWrappers.put(bridgeId, wrapper);
        }
    }

    public void bindGenerationFlush(String messageId, Consumer<StreamToken> consumer) {
        bindGenerationFlush(messageId, currentStreamEpoch(messageId), consumer);
    }

    public void bindGenerationFlush(String messageId, long epoch, Consumer<StreamToken> consumer) {
        if (messageId != null && consumer != null) {
            generationFlush.put(messageId, new FlushBinding(epoch, consumer));
        }
    }

    public void unbindGenerationFlush(String messageId) {
        if (messageId != null) {
            generationFlush.remove(messageId);
        }
    }

    public boolean hitlEnabled() {
        return hitlEnabledForBridge(resolveHitlBridgeId());
    }

    public boolean hitlEnabledForBridge(String bridgeId) {
        return bridgeId != null && Boolean.TRUE.equals(hitlEnabled.get(bridgeId));
    }

    public void grantHitlPreApproval(String messageId, String toolId, Map<String, String> params) {
        if (messageId == null || messageId.isBlank() || toolId == null || toolId.isBlank()) {
            return;
        }
        hitlPreapproved.put(messageId.strip(), hitlPreApprovalKey(toolId.strip(), params));
    }

    public boolean consumeHitlPreApproval(String messageId, String toolId, Map<String, String> params) {
        if (messageId == null || messageId.isBlank() || toolId == null || toolId.isBlank()) {
            return false;
        }
        String expected = hitlPreApprovalKey(toolId.strip(), params);
        return expected.equals(hitlPreapproved.remove(messageId.strip()));
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

    public void bindToolUseBridge(String toolUseId, String bridgeId) {
        if (toolUseId != null && !toolUseId.isBlank() && bridgeId != null && !bridgeId.isBlank()) {
            toolUseBridge.put(toolUseId.strip(), bridgeId.strip());
        }
    }

    public void unbindToolUseBridge(String toolUseId) {
        if (toolUseId != null && !toolUseId.isBlank()) {
            toolUseBridge.remove(toolUseId.strip());
        }
    }

    public String bridgeIdForToolUse(String toolUseId) {
        if (toolUseId == null || toolUseId.isBlank()) {
            return null;
        }
        return toolUseBridge.get(toolUseId.strip());
    }

    public String resolveHitlBridgeId() {
        if (toolUseBridge.size() == 1) {
            String fromToolUse = toolUseBridge.values().iterator().next();
            if (hitlEnabledForBridge(fromToolUse)) {
                return fromToolUse;
            }
        }
        if (sessions.size() == 1) {
            String id = sessions.keySet().iterator().next();
            if (hitlEnabledForBridge(id)) {
                return id;
            }
        }
        if (hitlEnabled.size() == 1) {
            return hitlEnabled.keySet().iterator().next();
        }
        return null;
    }

    public String activeBridgeId() {
        if (sessions.size() != 1) {
            return null;
        }
        return sessions.keySet().iterator().next();
    }

    public String hitlAssistantMessageId(String bridgeId) {
        if (bridgeId == null || bridgeId.isBlank()) {
            return null;
        }
        String mapped = hitlAssistantByBridge.get(bridgeId);
        if (mapped != null && !mapped.isBlank()) {
            return mapped;
        }
        return Boolean.TRUE.equals(hitlEnabled.get(bridgeId)) ? bridgeId : null;
    }

    public StepEventBridge.ToolAuditContext toolAuditContext(String messageId) {
        return messageId == null ? null : toolAuditContexts.get(messageId);
    }

    public String ragDetail(String messageId) {
        return messageId == null ? null : ragDetails.get(messageId);
    }

    public String userQuery(String messageId) {
        return messageId == null ? null : userQueries.get(messageId);
    }

    public String activeMessageId() {
        String bridge = activeBridgeId();
        if (bridge == null) {
            return null;
        }
        String mapped = hitlAssistantMessageId(bridge);
        return mapped != null ? mapped : bridge;
    }

    public void clear(String messageId) {
        if (messageId != null) {
            sessions.remove(messageId);
            hookTokenQueues.remove(messageId);
            ragDetails.remove(messageId);
            userQueries.remove(messageId);
            toolAuditContexts.remove(messageId);
            hitlEnabled.remove(messageId);
            hitlAssistantByBridge.remove(messageId);
            hitlPreapproved.remove(messageId);
            tokenWrappers.remove(messageId);
            generationFlush.remove(messageId);
            sessionStreamEpoch.remove(messageId);
            toolUseBridge.entrySet().removeIf(e -> messageId.equals(e.getValue()));
        }
    }

    public void clearForReactRestart(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        String msg = messageId.strip();
        String activeBridge = mainRunByMessage.remove(msg);
        if (activeBridge != null) {
            clear(activeBridge);
        }
        for (String bridgeId : java.util.List.copyOf(hitlAssistantByBridge.keySet())) {
            if (msg.equals(hitlAssistantByBridge.get(bridgeId))) {
                clear(bridgeId);
            }
        }
        clear(msg);
    }

    public void emit(String messageId, Consumer<ProcessingTimelineSession> action) {
        if (messageId == null || action == null || !isHookBridgeActive(messageId)) {
            return;
        }
        ProcessingTimelineSession session = sessions.get(messageId);
        if (session != null) {
            emitHookTokens(messageId, session, action);
        }
    }

    public void emitSingleton(Consumer<ProcessingTimelineSession> action) {
        if (action == null || sessions.size() != 1) {
            return;
        }
        sessions.forEach((id, session) -> emitHookTokens(id, session, action));
    }

    public void emitReasoningContentChunk(String messageId, String incrementalText) {
        if (messageId == null || incrementalText == null || incrementalText.isEmpty()) {
            return;
        }
        ProcessingTimelineSession session = sessions.get(messageId);
        if (session == null) {
            return;
        }
        List<StreamToken> emitted = ProcessingTimelineSupport.run(
                session, () -> session.ingestStreamingContentDelta(incrementalText));
        ConcurrentLinkedQueue<StreamToken> queue = hookTokenQueues.get(messageId);
        if (queue != null || generationFlush.containsKey(messageId)) {
            emitted.forEach(token -> routeHookToken(messageId, token, queue));
        }
    }

    public void emitReasoningChunk(String messageId, String incrementalText) {
        if (messageId == null || incrementalText == null || incrementalText.isEmpty()) {
            return;
        }
        ProcessingTimelineSession session = sessions.get(messageId);
        if (session == null) {
            return;
        }
        String thinkId = session.currentThinkStepId();
        if (thinkId == null || !session.isThinkRunning()) {
            return;
        }
        ConcurrentLinkedQueue<StreamToken> queue = hookTokenQueues.get(messageId);
        if (queue != null) {
            routeHookToken(messageId, StreamToken.stepDelta(thinkId, "reasoning", incrementalText), queue);
        }
    }

    public void emitSingletonReasoningChunk(String incrementalText) {
        if (incrementalText == null || incrementalText.isEmpty() || sessions.size() != 1) {
            return;
        }
        sessions.forEach((id, session) -> emitReasoningChunk(id, incrementalText));
    }

    private void emitHookTokens(String messageId, ProcessingTimelineSession session,
            Consumer<ProcessingTimelineSession> action) {
        List<StreamToken> hookEmitted = ProcessingTimelineSupport.run(session, () -> action.accept(session));
        ConcurrentLinkedQueue<StreamToken> queue = hookTokenQueues.get(messageId);
        if (queue != null || generationFlush.containsKey(messageId)) {
            hookEmitted.forEach(token -> routeHookToken(messageId, token, queue));
        }
    }

    private void routeHookToken(String messageId, StreamToken token,
            ConcurrentLinkedQueue<StreamToken> queue) {
        if (!isHookBridgeActive(messageId)) {
            return;
        }
        String flushKey = resolveFlushMessageId(messageId);
        FlushBinding binding = flushKey != null ? generationFlush.get(flushKey) : null;
        if (binding != null && isHookFlushAllowed(messageId, flushKey, binding.epoch())) {
            Consumer<StreamToken> sink = binding.consumer();
            Function<StreamToken, List<StreamToken>> wrapper = tokenWrappers.get(messageId);
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

    public boolean isHookBridgeActive(String bridgeId) {
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

    private String resolveFlushMessageId(String bridgeId) {
        if (bridgeId == null || bridgeId.isBlank()) {
            return null;
        }
        String assistantId = hitlAssistantMessageId(bridgeId);
        return assistantId != null ? assistantId : bridgeId;
    }

    private boolean isHookFlushAllowed(String bridgeId, String flushKey, long bindingEpoch) {
        Long sessionEpoch = sessionStreamEpoch.get(bridgeId);
        if (sessionEpoch == null || sessionEpoch != bindingEpoch) {
            return false;
        }
        return bindingEpoch == currentStreamEpoch(flushKey);
    }

    public void drainHookQueueToGeneration(String messageId,
            java.util.function.Consumer<StreamToken> tokenConsumer) {
        if (messageId == null || tokenConsumer == null || !isHookBridgeActive(messageId)) {
            return;
        }
        ConcurrentLinkedQueue<StreamToken> queue = hookTokenQueues.get(messageId);
        if (queue == null) {
            return;
        }
        StreamToken token;
        Function<StreamToken, List<StreamToken>> wrapper = tokenWrappers.get(messageId);
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
