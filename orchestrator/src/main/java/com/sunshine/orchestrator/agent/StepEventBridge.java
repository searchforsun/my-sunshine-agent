package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.sandbox.SandboxWriteHitlMode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Hook ↔ Timeline 静态门面 — 委托 {@link StepEventBridgeRegistry}。
 * AgentScope Hook 运行在非 Spring 线程，保留静态入口；状态由 Spring 单例 registry 托管。
 */
public final class StepEventBridge {

    private static volatile StepEventBridgeRegistry registry = new StepEventBridgeRegistry();

    /** ReAct / workflow 工具审计上下文 — 按 assistantMsgId 绑定 */
    public record ToolAuditContext(
            String conversationId,
            String messageId,
            String userId,
            String tenantId,
            String planId,
            String kbId) {
    }

    private StepEventBridge() {
    }

    /** Spring 启动时注入单例 registry */
    static void bindRegistry(StepEventBridgeRegistry r) {
        if (r != null) {
            registry = r;
        }
    }

    /** 单测隔离 */
    static void resetRegistry() {
        registry = new StepEventBridgeRegistry();
    }

    public static void registerMainRun(String assistantMessageId, String bridgeId) {
        registry.registerMainRun(assistantMessageId, bridgeId);
    }

    public static void unregisterMainRun(String assistantMessageId, String bridgeId) {
        registry.unregisterMainRun(assistantMessageId, bridgeId);
    }

    public static boolean isActiveMainBridge(String assistantMessageId, String bridgeId) {
        return registry.isActiveMainBridge(assistantMessageId, bridgeId);
    }

    public static String activeMainBridge(String assistantMessageId) {
        return registry.activeMainBridge(assistantMessageId);
    }

    public static void bind(String messageId, ProcessingTimelineSession session) {
        registry.bind(messageId, session);
    }

    public static void bind(String messageId, ProcessingTimelineSession session,
            ConcurrentLinkedQueue<StreamToken> hookTokenQueue) {
        registry.bind(messageId, session, hookTokenQueue);
    }

    public static long currentStreamEpoch(String messageId) {
        return registry.currentStreamEpoch(messageId);
    }

    public static long bumpStreamEpoch(String messageId) {
        return registry.bumpStreamEpoch(messageId);
    }

    public static boolean isStreamEpochValid(String messageId, long epoch) {
        return registry.isStreamEpochValid(messageId, epoch);
    }

    public static void setRagDetail(String messageId, String detail) {
        registry.setRagDetail(messageId, detail);
    }

    public static void setUserQuery(String messageId, String query) {
        registry.setUserQuery(messageId, query);
    }

    public static void bindToolAudit(String messageId, ToolAuditContext context) {
        registry.bindToolAudit(messageId, context);
    }

    public static void bindHitl(String messageId, boolean enabled) {
        registry.bindHitl(messageId, enabled);
    }

    public static void bindHitlBridge(String bridgeId, String assistantMessageId, boolean enabled) {
        registry.bindHitlBridge(bridgeId, assistantMessageId, enabled);
    }

    public static void bindTokenWrapper(String bridgeId, Function<StreamToken, List<StreamToken>> wrapper) {
        registry.bindTokenWrapper(bridgeId, wrapper);
    }

    public static void bindTokenWrapper(
            String bridgeId,
            Function<StreamToken, List<StreamToken>> wrapper,
            TokenWrapperMode mode) {
        registry.bindTokenWrapper(bridgeId, wrapper, mode);
    }

    /** loop 框内：Hook 直刷 Generation 前将 body 步折叠进 node-loop.subSteps */
    public static void bindLoopBodyFold(String assistantMessageId, Function<StreamToken, List<StreamToken>> fold) {
        registry.bindLoopBodyFold(assistantMessageId, fold);
    }

    public static void clearLoopBodyFold(String assistantMessageId) {
        registry.clearLoopBodyFold(assistantMessageId);
    }

    public static Function<StreamToken, List<StreamToken>> loopBodyFold(String assistantMessageId) {
        return registry.loopBodyFold(assistantMessageId);
    }

    }

    }

    }

    public static void bindGenerationFlush(String messageId, Consumer<StreamToken> consumer) {
        registry.bindGenerationFlush(messageId, consumer);
    }

    public static void bindGenerationFlush(String messageId, long epoch, Consumer<StreamToken> consumer) {
        registry.bindGenerationFlush(messageId, epoch, consumer);
    }

    public static void unbindGenerationFlush(String messageId) {
        registry.unbindGenerationFlush(messageId);
    }

    public static boolean hitlEnabled() {
        return registry.hitlEnabled();
    }

    public static boolean hitlEnabledForBridge(String bridgeId) {
        return registry.hitlEnabledForBridge(bridgeId);
    }

    public static void grantHitlPreApproval(String messageId, String toolId, Map<String, String> params) {
        registry.grantHitlPreApproval(messageId, toolId, params);
    }

    public static boolean consumeHitlPreApproval(String messageId, String toolId, Map<String, String> params) {
        return registry.consumeHitlPreApproval(messageId, toolId, params);
    }

    public static void bindWriteHitlMode(String assistantMessageId, SandboxWriteHitlMode mode) {
        registry.bindWriteHitlMode(assistantMessageId, mode);
    }

    public static SandboxWriteHitlMode writeHitlMode(String assistantMessageId) {
        return registry.writeHitlMode(assistantMessageId);
    }

    public static void bindToolUseBridge(String toolUseId, String bridgeId) {
        registry.bindToolUseBridge(toolUseId, bridgeId);
    }

    public static void bindToolUseStep(String toolUseId, String stepId) {
        registry.bindToolUseStep(toolUseId, stepId);
    }

    public static String stepIdForToolUse(String toolUseId) {
        return registry.stepIdForToolUse(toolUseId);
    }

    public static void unbindToolUseBridge(String toolUseId) {
        registry.unbindToolUseBridge(toolUseId);
    }

    public static String bridgeIdForToolUse(String toolUseId) {
        return registry.bridgeIdForToolUse(toolUseId);
    }

    public static String resolveHitlBridgeId() {
        return registry.resolveHitlBridgeId();
    }

    public static String activeBridgeId() {
        return registry.activeBridgeId();
    }

    public static String hitlAssistantMessageId(String bridgeId) {
        return registry.hitlAssistantMessageId(bridgeId);
    }

    public static ToolAuditContext toolAuditContext(String messageId) {
        return registry.toolAuditContext(messageId);
    }

    public static String ragDetail(String messageId) {
        return registry.ragDetail(messageId);
    }

    public static String userQuery(String messageId) {
        return registry.userQuery(messageId);
    }

    public static String activeMessageId() {
        return registry.activeMessageId();
    }

    public static void clear(String messageId) {
        registry.clear(messageId);
    }

    public static void clearForReactRestart(String messageId) {
        registry.clearForReactRestart(messageId);
    }

    public static void emit(String messageId, Consumer<ProcessingTimelineSession> action) {
        registry.emit(messageId, action);
    }

    /** Hook / 工具线程下发任意 StreamToken（如 sandbox_session） */
    public static void offerStreamToken(String bridgeId, StreamToken token) {
        registry.offerStreamToken(bridgeId, token);
    }

    public static void emitSingleton(Consumer<ProcessingTimelineSession> action) {
        registry.emitSingleton(action);
    }

    public static void emitReasoningContentChunk(String messageId, String incrementalText) {
        registry.emitReasoningContentChunk(messageId, incrementalText);
    }

    public static void emitReasoningChunk(String messageId, String incrementalText) {
        registry.emitReasoningChunk(messageId, incrementalText);
    }

    public static void emitSingletonReasoningChunk(String incrementalText) {
        registry.emitSingletonReasoningChunk(incrementalText);
    }

    public static boolean isHookBridgeActive(String bridgeId) {
        return registry.isHookBridgeActive(bridgeId);
    }

    public static void drainHookQueueToGeneration(String messageId,
            java.util.function.Consumer<StreamToken> tokenConsumer) {
        registry.drainHookQueueToGeneration(messageId, tokenConsumer);
    }
}
