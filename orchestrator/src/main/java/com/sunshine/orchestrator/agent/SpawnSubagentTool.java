package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import com.sunshine.orchestrator.agent.runtime.AgentRuntime;
import com.sunshine.orchestrator.catalog.ToolSetResolver;
import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.memory.MemoryContext;
import com.sunshine.orchestrator.processing.ContentBlock;
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

    private final AgentRuntime agentRuntime;
    private final AgentExecutionProperties executionProperties;
    private final SpawnSubagentTimelineSupport timelineSupport;
    private final ToolSetResolver toolSetResolver;

    public SpawnSubagentTool(
            @Lazy AgentRuntime agentRuntime,
            AgentExecutionProperties executionProperties,
            SpawnSubagentTimelineSupport timelineSupport,
            ToolSetResolver toolSetResolver) {
        this.agentRuntime = agentRuntime;
        this.executionProperties = executionProperties;
        this.timelineSupport = timelineSupport;
        this.toolSetResolver = toolSetResolver;
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
        // 与 MAIN 同 ReAct 工具集；SUB factory 不会注册 spawn_subagent / manage_tasks
        List<String> sameToolsAsMain = toolSetResolver.resolveReactTools(audit.tenantId());

        AgentRunRequest request = AgentRunRequest.sub(
                MemoryContext.forSubAgent(),
                promptText,
                List.of(),
                audit.userId(),
                audit.tenantId(),
                messageId,
                null,
                sameToolsAsMain,
                null,
                maxIters,
                audit.conversationId());
        String runId = request.runId();
        String subBridgeId = request.resolveBridgeId();
        SpawnSubagentTimelineBridge subTimeline =
                new SpawnSubagentTimelineBridge(runId, displayLabel, promptText);

        timelineSupport.begin(mainBridge, runId, displayLabel, promptText);
        // 子 Hook 可能经 generationFlush 走 wrapper（epoch=0 时常见），若只 return 空则 content 丢失；
        // 在 wrapper 内同步收集正文，并 fold 步骤；禁止把子 think/tool 原样刷进主时间线。
        StringBuilder answer = new StringBuilder();
        StepEventBridge.bindTokenWrapper(subBridgeId, token -> {
            appendAnswerContent(answer, token);
            foldStepToken(mainBridge, subTimeline, token);
            return List.of();
        });
        StepEventBridge.bindHitlBridge(subBridgeId, messageId, true);

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
            if (!StringUtils.hasText(result)) {
                result = fallbackAnswerFromSubSteps(subTimeline);
            }
            timelineSupport.complete(mainBridge, subTimeline, result);
            return result != null ? result : "";
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
        appendAnswerContent(answer, token);
        foldStepToken(mainBridge, subTimeline, token);
    }

    /** 收集子 Agent 面向主工具回传的正文（content / result delta）；reasoning 不计入 */
    static void appendAnswerContent(StringBuilder answer, StreamToken token) {
        if (answer == null || token == null || token.text() == null || token.text().isEmpty()) {
            return;
        }
        if (token.isContent()) {
            answer.append(token.text());
            return;
        }
        if (token.isStepDelta() && "result".equals(token.channel())) {
            answer.append(token.text());
        }
    }

    /** Hook 正文已进 think contentBlocks 但未出 content token 时的兜底 */
    static String fallbackAnswerFromSubSteps(SpawnSubagentTimelineBridge subTimeline) {
        if (subTimeline == null || subTimeline.subSteps().isEmpty()) {
            return "";
        }
        List<ProcessingStep> steps = subTimeline.subSteps();
        for (int i = steps.size() - 1; i >= 0; i--) {
            ProcessingStep step = steps.get(i);
            if (step == null) {
                continue;
            }
            if (StringUtils.hasText(step.result())) {
                return step.result().strip();
            }
            if (step.contentBlocks() != null) {
                StringBuilder sb = new StringBuilder();
                for (ContentBlock block : step.contentBlocks()) {
                    if (block != null && StringUtils.hasText(block.text())) {
                        sb.append(block.text());
                    }
                }
                if (!sb.isEmpty()) {
                    return sb.toString();
                }
            }
        }
        return "";
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
