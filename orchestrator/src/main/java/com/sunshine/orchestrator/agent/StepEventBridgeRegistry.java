package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.sandbox.SandboxWriteHitlMode;
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
    private final Map<String, String> toolUseStep = new ConcurrentHashMap<>();
    private final Map<String, String> hitlPreapproved = new ConcurrentHashMap<>();
    /** assistantMsgId → Chat 工作区写 HITL 跳过模式 */
    private final Map<String, SandboxWriteHitlMode> writeHitlModes = new ConcurrentHashMap<>();
    private final Map<String, Function<StreamToken, List<StreamToken>>> tokenWrappers = new ConcurrentHashMap<>();
    /** 与 tokenWrappers 同键；缺省 {@link TokenWrapperMode#EMIT_OUTGOING} */
    private final Map<String, TokenWrapperMode> tokenWrapperModes = new ConcurrentHashMap<>();
    /** assistantMsgId → loop body 折叠（Agent Hook 直刷 Generation 时用） */
    private final Map<String, Function<StreamToken, List<StreamToken>>> loopBodyFolds = new ConcurrentHashMap<>();
    private final Map<String, FlushBinding> generationFlush = new ConcurrentHashMap<>();
    private final Map<String, Long> streamEpoch = new ConcurrentHashMap<>();
    private final Map<String, Long> sessionStreamEpoch = new ConcurrentHashMap<>();
    /** 专家 Hub Sub-Agent：Hook 增量直出，不经主 Timeline think 锚点 */
    private final Map<String, Consumer<StreamToken>> expertSpeakSinks = new ConcurrentHashMap<>();

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
        toolUseStep.clear();
        hitlPreapproved.clear();
        writeHitlModes.clear();
        tokenWrappers.clear();
        tokenWrapperModes.clear();
        loopBodyFolds.clear();
        generationFlush.clear();
        streamEpoch.clear();
        sessionStreamEpoch.clear();
        expertSpeakSinks.clear();
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
            // bridgeId 可能是 main-{runId}/sub-{runId}，均非 streamEpoch 的键（其键是 assistantMessageId）。
            // 经 hitlAssistantMessageId 解析回 assistantMessageId 再取 epoch，否则恢复续跑 bumpStreamEpoch 后，
            // 新 spawn 的 sub bridge 会取到 0 与 bindingEpoch(N+1) 错配，isHookFlushAllowed 拒绝直刷 → 前端卡住。
            String epochKey = hitlAssistantMessageId(messageId);
            sessionStreamEpoch.put(messageId, currentStreamEpoch(epochKey != null ? epochKey : messageId));
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
        // merge(key,0,fn) 在 key 不存在时直接放 0（不调 fn），首次 bump 会得 0；改为显式 +1 保证单调递增。
        long next = streamEpoch.compute(key, (k, v) -> (v == null ? 0L : v) + 1);
        generationFlush.remove(key);
        // 同步抬高 sessionStreamEpoch：bindGenerationFlush 用新 epoch 绑定，
        // 若 session bind 尚未重放（flux 异步订阅），isHookFlushAllowed 仍因 sessionEpoch 旧而拒绝 flush，
        // 导致 spawn_subagent 等子 Agent token 无法直达 GenerationJob（卡到主 Agent drain）。
        // 恢复续跑时 main/sub bridge 键（main-*/sub-*）的 sessionStreamEpoch 也须在 bind 前抬到新 epoch，
        // 否则 resume run 的 bridge bind 记录旧 epoch，直刷与 drain 双双被 epoch 闸门拦截。
        sessionStreamEpoch.put(key, next);
        String activeBridge = mainRunByMessage.get(key);
        if (activeBridge != null) {
            sessionStreamEpoch.put(activeBridge, next);
        }
        for (Map.Entry<String, String> e : hitlAssistantByBridge.entrySet()) {
            if (key.equals(e.getValue())) {
                sessionStreamEpoch.put(e.getKey(), next);
            }
        }
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
                // 统一在此校准 sessionStreamEpoch：bridgeId（main-*/sub-*）非 streamEpoch 键，
                // bind 时 hitlAssistantByBridge 可能尚未注册（main 先 bind 后 bindHitl），
                // 故在映射确立后按 assistantMessageId 重取 epoch，保证恢复续跑 bump 后新 spawn 的
                // sub bridge 与 bindingEpoch 对齐，isHookFlushAllowed 放行直刷。
                if (sessions.containsKey(bridgeId)) {
                    sessionStreamEpoch.put(bridgeId, currentStreamEpoch(assistantMessageId));
                }
            }
        } else {
            hitlEnabled.remove(bridgeId);
            hitlAssistantByBridge.remove(bridgeId);
        }
    }

    public void bindTokenWrapper(String bridgeId, Function<StreamToken, List<StreamToken>> wrapper) {
        bindTokenWrapper(bridgeId, wrapper, TokenWrapperMode.EMIT_OUTGOING);
    }

    public void bindTokenWrapper(
            String bridgeId,
            Function<StreamToken, List<StreamToken>> wrapper,
            TokenWrapperMode mode) {
        if (bridgeId == null || wrapper == null) {
            return;
        }
        tokenWrappers.put(bridgeId, wrapper);
        tokenWrapperModes.put(bridgeId, mode != null ? mode : TokenWrapperMode.EMIT_OUTGOING);
    }

    public void bindLoopBodyFold(String assistantMessageId, Function<StreamToken, List<StreamToken>> fold) {
        if (assistantMessageId != null && !assistantMessageId.isBlank() && fold != null) {
            loopBodyFolds.put(assistantMessageId.strip(), fold);
        }
    }

    public void clearLoopBodyFold(String assistantMessageId) {
        if (assistantMessageId != null && !assistantMessageId.isBlank()) {
            loopBodyFolds.remove(assistantMessageId.strip());
        }
    }

    public Function<StreamToken, List<StreamToken>> loopBodyFold(String assistantMessageId) {
        if (assistantMessageId == null || assistantMessageId.isBlank()) {
            return null;
        }
        return loopBodyFolds.get(assistantMessageId.strip());
    }

    /** 专家发言专用：ReasoningChunk / 工具步等 Hook 产出即时消费，不依赖 ProcessingTimelineSession think 锚点 */
    public void bindExpertSpeakSink(String bridgeId, Consumer<StreamToken> sink) {
        if (bridgeId != null && sink != null) {
            expertSpeakSinks.put(bridgeId, sink);
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

    public void bindWriteHitlMode(String assistantMessageId, SandboxWriteHitlMode mode) {
        if (assistantMessageId == null || assistantMessageId.isBlank()) {
            return;
        }
        writeHitlModes.put(assistantMessageId.strip(),
                mode != null ? mode : SandboxWriteHitlMode.NEVER);
    }

    public SandboxWriteHitlMode writeHitlMode(String assistantMessageId) {
        if (assistantMessageId == null || assistantMessageId.isBlank()) {
            return SandboxWriteHitlMode.NEVER;
        }
        SandboxWriteHitlMode mode = writeHitlModes.get(assistantMessageId.strip());
        return mode != null ? mode : SandboxWriteHitlMode.NEVER;
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
            toolUseStep.remove(toolUseId.strip());
        }
    }

    public void bindToolUseStep(String toolUseId, String stepId) {
        if (toolUseId != null && !toolUseId.isBlank() && stepId != null && !stepId.isBlank()) {
            toolUseStep.put(toolUseId.strip(), stepId.strip());
        }
    }

    public String stepIdForToolUse(String toolUseId) {
        if (toolUseId == null || toolUseId.isBlank()) {
            return null;
        }
        return toolUseStep.get(toolUseId.strip());
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
            tokenWrapperModes.remove(messageId);
            loopBodyFolds.remove(messageId);
            generationFlush.remove(messageId);
            sessionStreamEpoch.remove(messageId);
            expertSpeakSinks.remove(messageId);
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

    public void offerStreamToken(String messageId, StreamToken token) {
        if (messageId == null || messageId.isBlank() || token == null) {
            return;
        }
        ConcurrentLinkedQueue<StreamToken> queue = hookTokenQueues.get(messageId);
        routeHookToken(messageId, token, queue);
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
        if (emitExpertSpeakText(messageId, incrementalText)) {
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
        if (emitExpertSpeakText(messageId, incrementalText)) {
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

    /** 专家 Hub：Hook 增量直出正文，不经 think 锚点 */
    public void emitExpertSpeakDelta(String bridgeId, String incrementalText) {
        emitExpertSpeakText(bridgeId, incrementalText);
    }

    /** 专家 Hub：工具调用开始时刷新 expert 步 active 文案 */
    public void emitExpertSpeakToolActive(String bridgeId, String toolLabel) {
        if (bridgeId == null || toolLabel == null || toolLabel.isBlank()) {
            return;
        }
        Consumer<StreamToken> sink = expertSpeakSinks.get(bridgeId);
        if (sink == null) {
            return;
        }
        long ts = System.currentTimeMillis();
        String active = toolLabel.strip() + "…";
        com.sunshine.orchestrator.processing.StepSummary summary =
                new com.sunshine.orchestrator.processing.StepSummary(null, active, null);
        ProcessingStep step = new ProcessingStep(
                "tool-expert-speak",
                "tool",
                "running",
                summary,
                ts,
                null,
                null,
                null,
                null,
                null,
                null,
                ts,
                toolLabel.strip(),
                null,
                null,
                null);
        sink.accept(StreamToken.step(step));
    }

    /** 专家 Hub：Hook 增量直出正文，不经 think 锚点 */
    private boolean emitExpertSpeakText(String bridgeId, String incrementalText) {
        if (!expertSpeakSinks.containsKey(bridgeId)) {
            return false;
        }
        Consumer<StreamToken> sink = expertSpeakSinks.get(bridgeId);
        sink.accept(StreamToken.content(incrementalText));
        return true;
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
        Consumer<StreamToken> expertSink = expertSpeakSinks.get(messageId);
        if (expertSink != null) {
            // 专家 Hub：正文走 agent.stream REASONING；Hook 仅即时下发工具步（工具 RPC 期间 stream 无事件）
            if (isExpertToolProgressToken(token)) {
                expertSink.accept(token);
            }
            return;
        }
        // 无 wrapper 的 sub Agent（专家 Hub 等）：禁止 Hook 刷入主 assistant 时间线
        if (messageId.startsWith("sub-") && !tokenWrappers.containsKey(messageId)) {
            if (queue != null) {
                queue.offer(token);
            }
            return;
        }
        String flushKey = resolveFlushMessageId(messageId);
        FlushBinding binding = flushKey != null ? generationFlush.get(flushKey) : null;
        boolean canFlush = binding != null && isHookFlushAllowed(messageId, flushKey, binding.epoch());
        WrapperOutcome outcome = applyTokenWrapper(messageId, token);
        if (canFlush) {
            if (outcome.mode() == TokenWrapperMode.PASS_THROUGH) {
                // fold 副作用已执行；原 token 入队供 Flux，勿把空/父步刷进 Generation
                if (queue != null) {
                    queue.offer(token);
                }
                return;
            }
            if (!outcome.outgoing().isEmpty()) {
                flushToGeneration(flushKey, binding.consumer(), outcome.outgoing());
                return;
            }
            // EMIT_OUTGOING + 空输出：丢弃
            return;
        }
        if (queue != null) {
            queue.offer(token);
        }
    }

    /**
     * 执行 wrapper 副作用并解析输出；route / drain 共用，禁止再靠 sub- + 空列表猜测。
     */
    private WrapperOutcome applyTokenWrapper(String bridgeId, StreamToken token) {
        Function<StreamToken, List<StreamToken>> wrapper = tokenWrappers.get(bridgeId);
        if (wrapper == null) {
            return new WrapperOutcome(TokenWrapperMode.EMIT_OUTGOING, List.of(token));
        }
        TokenWrapperMode mode = tokenWrapperModes.getOrDefault(bridgeId, TokenWrapperMode.EMIT_OUTGOING);
        List<StreamToken> outgoing = wrapper.apply(token);
        if (outgoing == null) {
            outgoing = List.of();
        }
        return new WrapperOutcome(mode, outgoing);
    }

    private record WrapperOutcome(TokenWrapperMode mode, List<StreamToken> outgoing) {
    }

    private void flushToGeneration(
            String flushKey,
            Consumer<StreamToken> sink,
            List<StreamToken> tokens) {
        if (sink == null || tokens == null || tokens.isEmpty()) {
            return;
        }
        Function<StreamToken, List<StreamToken>> fold =
                flushKey != null ? loopBodyFolds.get(flushKey) : null;
        for (StreamToken t : tokens) {
            if (fold != null) {
                List<StreamToken> folded = fold.apply(t);
                if (folded != null) {
                    folded.forEach(sink);
                }
            } else {
                sink.accept(t);
            }
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

    private static boolean isExpertToolProgressToken(StreamToken token) {
        if (token == null || !token.isStep() || token.step() == null) {
            return false;
        }
        String phase = token.step().phase();
        return phase != null && phase.startsWith("tool");
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
        String flushKey = resolveFlushMessageId(messageId);
        while ((token = queue.poll()) != null) {
            WrapperOutcome outcome = applyTokenWrapper(messageId, token);
            // PASS_THROUGH：副作用（fold）已执行；原 token 仅 Flux 消费，不刷 Generation
            if (outcome.mode() == TokenWrapperMode.PASS_THROUGH) {
                continue;
            }
            List<StreamToken> outgoing = outcome.outgoing();
            if (outgoing.isEmpty()) {
                continue;
            }
            Function<StreamToken, List<StreamToken>> fold =
                    flushKey != null ? loopBodyFolds.get(flushKey) : null;
            for (StreamToken t : outgoing) {
                if (fold != null) {
                    List<StreamToken> folded = fold.apply(t);
                    if (folded != null) {
                        folded.forEach(tokenConsumer);
                    }
                } else {
                    tokenConsumer.accept(t);
                }
            }
        }
    }
}
