package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.memory.MemoryContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
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

    private final AgentRuntime agentRuntime;
    private final AgentExecutionProperties executionProperties;
    private final SpawnSubagentTimelineSupport timelineSupport;

    public SpawnSubagentTool(
            @Lazy AgentRuntime agentRuntime,
            AgentExecutionProperties executionProperties,
            SpawnSubagentTimelineSupport timelineSupport) {
        this.agentRuntime = agentRuntime;
        this.executionProperties = executionProperties;
        this.timelineSupport = timelineSupport;
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

        AgentRunRequest request = AgentRunRequest.sub(
                MemoryContext.forSubAgent(),
                promptText,
                List.of(),
                audit.userId(),
                audit.tenantId(),
                messageId,
                null,
                null,
                null,
                maxIters,
                audit.conversationId());
        String runId = request.runId();
        String subBridgeId = request.resolveBridgeId();
        SpawnSubagentTimelineBridge subTimeline =
                new SpawnSubagentTimelineBridge(runId, displayLabel, promptText);

        timelineSupport.begin(mainBridge, runId, displayLabel, promptText);
        StepEventBridge.bindTokenWrapper(subBridgeId, token -> {
            foldStepToken(mainBridge, subTimeline, token);
            return List.of();
        });
        StepEventBridge.bindHitlBridge(subBridgeId, messageId, true);

        StringBuilder answer = new StringBuilder();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            agentRuntime.run(request)
                    .doOnNext(token -> ingestToken(mainBridge, subTimeline, answer, token))
                    .doOnError(failure::set)
                    .blockLast(Duration.ofMillis(timeoutMs));
            if (failure.get() != null) {
                return failAndReturn(mainBridge, subTimeline, failure.get());
            }
            String result = answer.toString();
            timelineSupport.complete(mainBridge, subTimeline, result);
            return result;
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (isTimeout(cause) || isTimeout(e)) {
                String msg = "子 Agent 执行超时（" + timeoutMs + "ms）";
                timelineSupport.fail(mainBridge, subTimeline, msg);
                return msg;
            }
            return failAndReturn(mainBridge, subTimeline, cause != null ? cause : e);
        }
    }

    private void ingestToken(
            String mainBridge,
            SpawnSubagentTimelineBridge subTimeline,
            StringBuilder answer,
            StreamToken token) {
        if (token == null) {
            return;
        }
        if (token.isContent() && token.text() != null) {
            answer.append(token.text());
            return;
        }
        foldStepToken(mainBridge, subTimeline, token);
    }

    private void foldStepToken(
            String mainBridge, SpawnSubagentTimelineBridge subTimeline, StreamToken token) {
        if (token != null && (token.isStep() || token.isStepDelta())) {
            timelineSupport.fold(mainBridge, subTimeline, token);
        }
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
