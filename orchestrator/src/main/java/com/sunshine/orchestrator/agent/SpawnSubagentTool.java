package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.config.PromptOverlayProperties;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.processing.ContentSegmentCoordinator;
import com.sunshine.orchestrator.processing.SpawnSubagentLabels;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** ReAct 元工具 — 主 Agent 按需创建隔离子 Agent，不占 tool-manager Catalog */
@Slf4j
@Component
public class SpawnSubagentTool {

    public static final String NAME = "spawn_subagent";
    /** Nacos agent.prompt.mode-overlays.subagent */
    public static final String OVERLAY_KEY = "subagent";

    private final AgentRuntime agentRuntime;
    private final AgentExecutionProperties executionProperties;
    private final SpawnSubagentTimelineSupport timelineSupport;
    private final ToolSetResolver toolSetResolver;
    private final PromptOverlayProperties overlayProperties;
    private final SpawnRunRegistry spawnRunRegistry;

    public SpawnSubagentTool(
            @Lazy AgentRuntime agentRuntime,
            AgentExecutionProperties executionProperties,
            SpawnSubagentTimelineSupport timelineSupport,
            ToolSetResolver toolSetResolver,
            PromptOverlayProperties overlayProperties,
            SpawnRunRegistry spawnRunRegistry) {
        this.agentRuntime = agentRuntime;
        this.executionProperties = executionProperties;
        this.timelineSupport = timelineSupport;
        this.toolSetResolver = toolSetResolver;
        this.overlayProperties = overlayProperties;
        this.spawnRunRegistry = spawnRunRegistry;
    }

    @Tool(name = NAME,
            description = "创建隔离子 Agent：传入完整 prompt，返回子任务最终文本；用于避免主上下文膨胀。")
    public String spawnSubagent(
            @ToolParam(name = "prompt", description = "给子 Agent 的完整任务说明（必填）") String prompt,
            @ToolParam(name = "label", description = "时间线卡片短标题（可选）") String label) {
        AgentExecutionProperties.React.Subagent subCfg = subagentConfig();
        if (subCfg == null || !subCfg.isEnabled()) {
            return errorJson("spawn_subagent 未启用");
        }
        if (!StringUtils.hasText(prompt)) {
            return errorJson("prompt 不能为空");
        }
        String messageId = StepEventBridge.activeMessageId();
        if (!StringUtils.hasText(messageId)) {
            return errorJson("无法定位当前会话消息");
        }
        StepEventBridge.ToolAuditContext audit = StepEventBridge.toolAuditContext(messageId);
        if (audit == null
                || !StringUtils.hasText(audit.userId())
                || !StringUtils.hasText(audit.tenantId())
                || !StringUtils.hasText(audit.conversationId())) {
            return errorJson("缺少会话审计上下文（userId/tenantId/conversationId）");
        }
        String mainBridge = StepEventBridge.activeMainBridge(messageId);
        if (!StringUtils.hasText(mainBridge)) {
            return errorJson("spawn_subagent 仅可从主 Agent 调用");
        }
        String activeBridge = StepEventBridge.activeBridgeId();
        if (StringUtils.hasText(activeBridge) && activeBridge.startsWith("sub-")) {
            return errorJson("禁止嵌套委派：子 Agent 不可再调用 spawn_subagent");
        }

        String promptText = prompt.strip();
        String displayLabel = StringUtils.hasText(label) ? label.strip() : SpawnSubagentLabels.label();
        int maxIters = subCfg.getMaxIters() > 0 ? subCfg.getMaxIters() : 8;
        long timeoutMs = subCfg.getTimeoutMs() > 0 ? subCfg.getTimeoutMs() : 180_000L;
        List<String> sameToolsAsMain = toolSetResolver.resolveReactTools(audit.tenantId());
        String systemOverlay = resolveSubagentOverlay();

        AgentRunRequest request = AgentRunRequest.sub(
                MemoryContext.forSubAgent(),
                promptText,
                List.of(),
                audit.userId(),
                audit.tenantId(),
                messageId,
                null,
                sameToolsAsMain,
                systemOverlay,
                maxIters,
                audit.conversationId());
        String runId = request.runId();
        String subBridgeId = request.resolveBridgeId();
        SpawnSubagentTimelineBridge subTimeline =
                new SpawnSubagentTimelineBridge(runId, displayLabel, promptText);

        timelineSupport.begin(mainBridge, runId, displayLabel, promptText);
        spawnRunRegistry.register(runId, messageId, promptText, mainBridge, subTimeline);
        // PASS_THROUGH：wrapper 只 fold；原 token 入队供 Flux（禁止 Flux 再 fold，否则 reasoning 翻倍）
        StepEventBridge.bindTokenWrapper(subBridgeId, token -> {
            foldStepToken(mainBridge, subTimeline, token);
            return List.of();
        }, TokenWrapperMode.PASS_THROUGH);
        StepEventBridge.bindHitlBridge(subBridgeId, messageId, true);

        StringBuilder answer = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            agentRuntime.run(request)
                    .doOnNext(token -> appendAnswerContent(answer, token))
                    .doOnError(failure::set)
                    .blockLast(Duration.ofMillis(timeoutMs));
            if (spawnRunRegistry.isCancelled(runId)) {
                return cancelAndReturn(mainBridge, subTimeline, promptText);
            }
            if (failure.get() != null) {
                if (isInterrupted(failure.get())) {
                    return cancelAndReturn(mainBridge, subTimeline, promptText);
                }
                return failAndReturn(mainBridge, subTimeline, failure.get());
            }
            String result = answer.toString();
            timelineSupport.complete(mainBridge, subTimeline, result);
            if (!StringUtils.hasText(result)) {
                log.warn("[SpawnSubagentTool] 子 Agent 未产出正文 content runId={}", runId);
            }
            return result;
        } catch (Exception e) {
            if (spawnRunRegistry.isCancelled(runId) || isInterrupted(e)) {
                return cancelAndReturn(mainBridge, subTimeline, promptText);
            }
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (isTimeout(cause) || isTimeout(e)) {
                String msg = "子 Agent 执行超时（" + timeoutMs + "ms）";
                timelineSupport.fail(mainBridge, subTimeline, msg);
                return msg;
            }
            return failAndReturn(mainBridge, subTimeline, cause != null ? cause : e);
        } finally {
            spawnRunRegistry.unregister(runId);
        }
    }

    /**
     * 收集正文：与 {@link ContentSegmentCoordinator} 相同单调规则。
     * AGENT_RESULT 全量复读不得再 append（否则 complete 终稿翻倍，前端 longerText 会吃更长串）。
     */
    static void appendAnswerContent(StringBuilder answer, StreamToken token) {
        if (answer == null || token == null || token.text() == null || token.text().isEmpty()) {
            return;
        }
        if (!token.isContent() && !(token.isStepDelta() && "result".equals(token.channel()))) {
            return;
        }
        String incoming = token.text();
        String baseline = answer.toString();
        String delta = ContentSegmentCoordinator.resolveDelta(baseline, incoming);
        if (delta.isEmpty()) {
            return;
        }
        answer.setLength(0);
        answer.append(ContentSegmentCoordinator.advanceBaseline(baseline, incoming, delta));
    }

    private String resolveSubagentOverlay() {
        if (overlayProperties == null || overlayProperties.getModeOverlays() == null) {
            return null;
        }
        String text = overlayProperties.getModeOverlays().get(OVERLAY_KEY);
        return StringUtils.hasText(text) ? text.strip() : null;
    }

    private void foldStepToken(
            String mainBridge, SpawnSubagentTimelineBridge subTimeline, StreamToken token) {
        if (token == null) {
            return;
        }
        // step / step_delta → subSteps；content → 父卡 result 流式（见 Bridge.wrap）
        if (token.isStep() || token.isStepDelta()
                || token.isContent() || token.isContentStart() || token.isContentEnd()) {
            timelineSupport.fold(mainBridge, subTimeline, token);
        }
    }

    /**
     * Registry.cancel 已下发父卡 paused 时不再二次 emit；仅 interrupt 未走 Registry 时补终态。
     */
    private String cancelAndReturn(
            String mainBridge, SpawnSubagentTimelineBridge subTimeline, String prompt) {
        String result = spawnRunRegistry.formatCancelResult(prompt);
        log.info("[SpawnSubagentTool] 子任务已取消，回主 Agent 接手");
        if (subTimeline != null && !subTimeline.userCancelled()) {
            timelineSupport.cancel(mainBridge, subTimeline, result);
        }
        return result;
    }

    private String failAndReturn(
            String mainBridge, SpawnSubagentTimelineBridge subTimeline, Throwable error) {
        String msg = error != null && StringUtils.hasText(error.getMessage())
                ? error.getMessage().strip()
                : "子 Agent 执行失败";
        log.warn("[SpawnSubagentTool] 子 Agent 失败: {}", msg);
        timelineSupport.fail(mainBridge, subTimeline, msg);
        return msg;
    }

    private AgentExecutionProperties.React.Subagent subagentConfig() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null ? react.getSubagent() : null;
    }

    private static boolean isTimeout(Throwable t) {
        if (t == null) {
            return false;
        }
        if (t instanceof TimeoutException) {
            return true;
        }
        String name = t.getClass().getName();
        return name.contains("Timeout")
                || (t.getMessage() != null && t.getMessage().contains("Did not observe"));
    }

    private static boolean isInterrupted(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof InterruptedException) {
                return true;
            }
            String name = cur.getClass().getSimpleName();
            if (name.contains("Interrupt")) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null && msg.toLowerCase().contains("interrupt")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String errorJson(String message) {
        return "{\"ok\":false,\"error\":\"" + escape(message) + "\"}";
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
