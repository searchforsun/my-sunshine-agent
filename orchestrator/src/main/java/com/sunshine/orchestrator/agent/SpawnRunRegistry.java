package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.SpawnSubagentLabels;
import com.sunshine.orchestrator.generation.GenerationJob;
import com.sunshine.orchestrator.generation.GenerationRegistry;
import com.sunshine.orchestrator.prompt.PromptCatalogHolder;
import io.agentscope.core.agent.Agent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 运行中 spawn_subagent 句柄 — 按 runId 单独 interrupt，禁止 bump 整轮 stream epoch。
 */
@Slf4j
@Component
public class SpawnRunRegistry {

    private final ConcurrentHashMap<String, Handle> byRunId = new ConcurrentHashMap<>();
    private final GenerationRegistry generationRegistry;
    private final PromptCatalogHolder promptCatalogHolder;

    @Autowired
    public SpawnRunRegistry(
            @Lazy GenerationRegistry generationRegistry,
            PromptCatalogHolder promptCatalogHolder) {
        this.generationRegistry = generationRegistry;
        this.promptCatalogHolder = promptCatalogHolder;
    }

    /** 单测无 Spring 时用 */
    public static SpawnRunRegistry forTest() {
        return new SpawnRunRegistry(null, null);
    }

    /** 单测：注入 Catalog */
    public static SpawnRunRegistry forTest(PromptCatalogHolder promptCatalogHolder) {
        return new SpawnRunRegistry(null, promptCatalogHolder);
    }

    public void register(
            String runId,
            String messageId,
            String prompt,
            String mainBridgeId,
            SpawnSubagentTimelineBridge timelineBridge) {
        if (!StringUtils.hasText(runId)) {
            return;
        }
        if (!StringUtils.hasText(messageId)) {
            log.warn("[SpawnRunRegistry] register skip: blank messageId runId={}", runId);
            return;
        }
        String id = runId.strip();
        Handle handle = new Handle(
                id,
                messageId.strip(),
                prompt,
                mainBridgeId,
                timelineBridge);
        byRunId.put(id, handle);
        log.debug("[SpawnRunRegistry] register runId={}", id);
    }

    /**
     * 用户取消子卡后的回调（如 AsyncToolRunRegistry.complete CANCELLED）。
     * 须在 {@link #cancel} 成功 CAS 后触发一次。
     */
    public void bindOnUserCancel(String runId, Runnable action) {
        if (!StringUtils.hasText(runId) || action == null) {
            return;
        }
        Handle handle = byRunId.get(runId.strip());
        if (handle == null) {
            return;
        }
        handle.onUserCancel = action;
        if (handle.cancelled.get()) {
            fireOnUserCancel(handle);
        }
    }

    /** ReActAgentRuntime 创建 SUB agent 后绑定，供 cancel → interrupt */
    public void bindAgent(String runId, Agent agent) {
        if (!StringUtils.hasText(runId) || agent == null) {
            return;
        }
        Handle handle = byRunId.get(runId.strip());
        if (handle == null) {
            return;
        }
        handle.agentRef.set(agent);
        if (handle.cancelled.get()) {
            safeInterrupt(agent, runId.strip());
        }
    }

    public boolean isCancelled(String runId) {
        if (!StringUtils.hasText(runId)) {
            return false;
        }
        Handle handle = byRunId.get(runId.strip());
        return handle != null && handle.cancelled.get();
    }

    public Handle get(String runId) {
        if (!StringUtils.hasText(runId)) {
            return null;
        }
        return byRunId.get(runId.strip());
    }

    /**
     * 取消指定子任务。成功返回 true；未知 runId 返回 false。
     * 立即下发父卡 paused SSE（直写 GenerationJob，避免 Hook 队列在 tool.block 期间滞留）；
     * 不调用 GenerationRegistry.cancel / bumpStreamEpoch。
     */
    public boolean cancel(String runId) {
        if (!StringUtils.hasText(runId)) {
            return false;
        }
        String id = runId.strip();
        Handle handle = byRunId.get(id);
        if (handle == null) {
            log.info("[SpawnRunRegistry] cancel miss runId={}", runId);
            return false;
        }
        if (!handle.cancelled.compareAndSet(false, true)) {
            return true;
        }
        SpawnSubagentTimelineBridge bridge = handle.timelineBridge;
        if (bridge != null) {
            bridge.markUserCancelled();
        }
        String result = formatCancelResult(handle.prompt);
        if (bridge != null) {
            List<StreamToken> tokens = bridge.cancel(SpawnSubagentLabels.afterCancel(), result);
            // 禁止回落 Hook 队列：MAIN 在 spawn tool.block 期间不 drain，会导致 UI 仍 running
            if (!flushCancelTokens(handle, tokens)) {
                log.warn("[SpawnRunRegistry] cancel SSE 未直达 GenerationJob runId={} messageId={}",
                        id, handle.messageId);
            }
        }
        Agent agent = handle.agentRef.get();
        if (agent != null) {
            safeInterrupt(agent, id);
        }
        fireOnUserCancel(handle);
        log.info("[SpawnRunRegistry] cancel runId={} messageId={}", id, handle.messageId);
        return true;
    }

    private static void fireOnUserCancel(Handle handle) {
        Runnable action = handle.onUserCancel;
        if (action == null || !handle.onUserCancelFired.compareAndSet(false, true)) {
            return;
        }
        try {
            action.run();
        } catch (Exception e) {
            log.warn("[SpawnRunRegistry] onUserCancel failed runId={}: {}", handle.runId, e.getMessage());
        }
    }

    /**
     * interrupt 路径补终态：与 {@link #cancel} 同写 GenerationJob，不经 Hook。
     * 调用方须确认尚未经 {@link #cancel}（bridge.userCancelled 仍为 false）。
     */
    public void flushCancelTerminal(String runId, SpawnSubagentTimelineBridge bridge, String result) {
        if (bridge == null) {
            return;
        }
        Handle handle = StringUtils.hasText(runId) ? byRunId.get(runId.strip()) : null;
        if (handle != null) {
            handle.cancelled.set(true);
        }
        List<StreamToken> tokens = bridge.cancel(SpawnSubagentLabels.afterCancel(), result);
        if (!flushCancelTokens(handle, tokens)) {
            log.warn("[SpawnRunRegistry] flushCancelTerminal miss job runId={} messageId={}",
                    runId, handle != null ? handle.messageId : null);
        }
    }

    /**
     * 取消终态下发：Worker 内 spawn 时经 worker 桥折叠进 {@code worker-{taskId}.subSteps}
     * （WorkerTimelineBridge.wrap 直刷父步快照，抽屉内子 Agent 卡更新为已取消，而非主时间线顶层）；
     * 主 Agent spawn 直写 GenerationJob（tool.block 期间 Hook 队列不 drain，保证 UI 立即终态）。
     */
    private boolean flushCancelTokens(Handle handle, List<StreamToken> tokens) {
        if (handle != null && tokens != null && !tokens.isEmpty()) {
            String bridgeId = handle.mainBridgeId();
            if (StringUtils.hasText(bridgeId) && bridgeId.startsWith("worker-")
                    && StepEventBridge.hasSession(bridgeId)) {
                StepEventBridge.emit(bridgeId, s -> tokens.forEach(s::enqueueAuxiliary));
                return true;
            }
        }
        String messageId = handle != null ? handle.messageId() : null;
        return flushCancelToGeneration(messageId, tokens);
    }

    /** @return true 若已直写 GenerationJob */
    private boolean flushCancelToGeneration(String messageId, List<StreamToken> tokens) {
        if (!StringUtils.hasText(messageId) || tokens == null || tokens.isEmpty()) {
            log.warn("[SpawnRunRegistry] flushCancel skip: messageId/tokens empty");
            return false;
        }
        if (generationRegistry == null) {
            return false;
        }
        GenerationJob job = generationRegistry.findByMessageId(messageId.strip()).orElse(null);
        if (job == null) {
            log.warn("[SpawnRunRegistry] flushCancel miss job messageId={}", messageId);
            return false;
        }
        for (StreamToken token : tokens) {
            job.emitStreamToken(token);
        }
        log.info("[SpawnRunRegistry] flushCancel ok messageId={} tokens={} lifecycle={}",
                messageId, tokens.size(),
                tokens.get(0).step() != null ? tokens.get(0).step().lifecycle() : null);
        return true;
    }

    public void unregister(String runId) {
        if (!StringUtils.hasText(runId)) {
            return;
        }
        byRunId.remove(runId.strip());
    }

    /** 与 SSE 父卡 result / tool result 同模板（SSOT = Catalog react.subagent.cancel-result） */
    public String formatCancelResult(String prompt) {
        String tpl = promptCatalogHolder != null
                ? promptCatalogHolder.requireText("react.subagent.cancel-result").strip()
                : "";
        if (!StringUtils.hasText(tpl)) {
            log.warn("[SpawnRunRegistry] catalog missing id=react.subagent.cancel-result");
            return prompt != null ? prompt : "";
        }
        return tpl.replace("{prompt}", prompt != null ? prompt : "");
    }

    private static void safeInterrupt(Agent agent, String runId) {
        try {
            agent.interrupt();
        } catch (Exception e) {
            log.warn("[SpawnRunRegistry] interrupt failed runId={}: {}", runId, e.getMessage());
        }
    }

    public static final class Handle {
        private final String runId;
        private final String messageId;
        private final String prompt;
        private final String mainBridgeId;
        private final SpawnSubagentTimelineBridge timelineBridge;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<Agent> agentRef = new AtomicReference<>();
        private volatile Runnable onUserCancel;
        private final AtomicBoolean onUserCancelFired = new AtomicBoolean(false);

        Handle(
                String runId,
                String messageId,
                String prompt,
                String mainBridgeId,
                SpawnSubagentTimelineBridge timelineBridge) {
            this.runId = runId;
            this.messageId = messageId;
            this.prompt = prompt;
            this.mainBridgeId = mainBridgeId;
            this.timelineBridge = timelineBridge;
        }

        public String runId() {
            return runId;
        }

        public String messageId() {
            return messageId;
        }

        public String prompt() {
            return prompt;
        }

        public String mainBridgeId() {
            return mainBridgeId;
        }

        public SpawnSubagentTimelineBridge timelineBridge() {
            return timelineBridge;
        }

        public boolean cancelled() {
            return cancelled.get();
        }
    }
}
