package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.hitl.HitlParamSupport;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.StepMetadata;
import com.sunshine.orchestrator.processing.ToolExpandDetailSupport;
import com.sunshine.orchestrator.sandbox.CancellableToolRunRegistry;
import com.sunshine.orchestrator.sandbox.SandboxCancelExpand;
import com.sunshine.orchestrator.sandbox.SandboxIds;
import com.sunshine.orchestrator.sandbox.SandboxStepContext;
import com.sunshine.orchestrator.sandbox.SandboxTimelineLabelService;
import com.sunshine.orchestrator.taskboard.TaskBoardTimelineSupport;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentScope Hook — ReAct 轮次边界与 reasoning 增量（唯一触达源）
 * <ul>
 *   <li>PreReasoning / PostReasoning：think 开闭（正文仅 ReasoningChunk 流式，PostReasoning 不灌快照）</li>
 *   <li>ReasoningChunkEvent：think step_delta + TextBlock 正文段（原生 incrementalChunk，即时刷 SSE）</li>
 *   <li>PreActing / PostActing：工具步骤</li>
 * </ul>
 * 每个 ReAct 请求通过 {@link ProcessingStepHookFactory} 绑定独立 bridgeId，并发安全。
 */
public class ProcessingStepHook implements Hook {

    private final String bridgeId;
    private final ToolCatalogService toolCatalogService;
    private final AgentExecutionProperties executionProperties;
    private final TaskBoardTimelineSupport taskBoardTimelineSupport;
    private final SandboxTimelineLabelService sandboxTimelineLabels;
    private final CancellableToolRunRegistry cancellableToolRunRegistry;

    ProcessingStepHook(
            String bridgeId,
            ToolCatalogService toolCatalogService,
            AgentExecutionProperties executionProperties,
            TaskBoardTimelineSupport taskBoardTimelineSupport,
            SandboxTimelineLabelService sandboxTimelineLabels,
            CancellableToolRunRegistry cancellableToolRunRegistry) {
        this.bridgeId = bridgeId;
        this.toolCatalogService = toolCatalogService;
        this.executionProperties = executionProperties;
        this.taskBoardTimelineSupport = taskBoardTimelineSupport;
        this.sandboxTimelineLabels = sandboxTimelineLabels;
        this.cancellableToolRunRegistry = cancellableToolRunRegistry;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof io.agentscope.core.hook.PreReasoningEvent) {
            StepEventBridge.emit(bridgeId, ProcessingTimelineSession::beginReasoningRound);
            return Mono.just(event);
        }

        if (event instanceof PostReasoningEvent post) {
            StepEventBridge.emit(bridgeId, session -> {
                session.endReasoningRound();
                if (isTaskBoardEnabled()) {
                    taskBoardTimelineSupport.ensurePlaceholderAfterFirstThink(session);
                }
            });
            return Mono.just(event);
        }

        if (event instanceof ReasoningChunkEvent chunkEvent) {
            String thinkDelta = ReasoningChunkSupport.extractIncrementalText(chunkEvent);
            StepEventBridge.emitReasoningChunk(bridgeId, thinkDelta);
            String contentDelta = ReasoningChunkSupport.extractIncrementalContent(chunkEvent);
            StepEventBridge.emitReasoningContentChunk(bridgeId, contentDelta);
            return Mono.just(event);
        }

        if (event instanceof PreActingEvent pre) {
            String toolName = pre.getToolUse().getName();
            if (ManageTasksTool.NAME.equals(toolName) || SpawnSubagentTool.NAME.equals(toolName)) {
                return Mono.just(event);
            }
            String toolUseId = pre.getToolUse().getId();
            StepEventBridge.bindToolUseBridge(toolUseId, bridgeId);
            String baseStepId = toolCatalogService.timelineStepId(toolName);
            String phase = toolCatalogService.timelinePhase(toolName);
            Map<String, Object> input = toolInput(pre.getToolUse());
            String sandboxActive = sandboxActiveSummary(toolName, input);
            final String[] stepHolder = new String[1];
            boolean cancellable = cancellableToolRunRegistry.isCancellableTool(toolName)
                    && StringUtils.hasText(toolUseId);
            StepEventBridge.emit(bridgeId, session -> {
                session.noteToolCallPending();
                stepHolder[0] = session.beginToolStep(baseStepId, phase);
                if (sandboxActive != null) {
                    session.progressCurrentToolStep(sandboxActive);
                }
                if (cancellable) {
                    session.markCurrentToolCancellable();
                }
            });
            if (stepHolder[0] != null) {
                StepEventBridge.bindToolUseStep(toolUseId, stepHolder[0]);
            }
            // 出卡即登记：UI 可立即 cancel，无需等 execute 入口（messageId 必须为 assistant，禁止 bridgeId 冒充）
            if (cancellable) {
                String messageId = StepEventBridge.hitlAssistantMessageId(bridgeId);
                if (!StringUtils.hasText(messageId)) {
                    messageId = StepEventBridge.activeMessageId();
                }
                if (StringUtils.hasText(messageId)) {
                    String expandDetail = SandboxCancelExpand.detail(toolName, input);
                    cancellableToolRunRegistry.register(
                            toolUseId, messageId, toolName, null, toolUseId, expandDetail);
                }
            }
            return Mono.just(event);
        }

        if (event instanceof PostActingEvent post) {
            String toolName = post.getToolUse().getName();
            String toolUseId = post.getToolUse().getId();
            if (ManageTasksTool.NAME.equals(toolName)) {
                StepEventBridge.unbindToolUseBridge(toolUseId);
                return Mono.just(event);
            }
            // spawn_subagent 不上 tool-* 步，但须 recordToolCompleted，否则后续推理合并进首个 think
            if (SpawnSubagentTool.NAME.equals(toolName)) {
                StepEventBridge.emit(bridgeId, session ->
                        session.recordToolCompleted(SpawnSubagentLabels.label()));
                StepEventBridge.unbindToolUseBridge(toolUseId);
                return Mono.just(event);
            }
            String rawText = extractToolResultText(post.getToolResult());
            final String summaryLine;
            final String expandDetail;
            if (toolCatalogService.isRagTool(toolName)) {
                // search_knowledge 无 catalog 摘要模板；metadata/after 须从工具原始命中正文解析
                summaryLine = rawText;
                expandDetail = ToolExpandDetailSupport.resolveExpandDetail(null, rawText);
            } else if (sandboxTimelineLabels.isSandboxTool(toolName)) {
                Map<String, Object> input = toolInput(post.getToolUse());
                String raw = rawText != null ? rawText.strip() : "";
                // 用户单工具取消：Controller 已 pause 终态，禁止再 complete 覆盖
                if (cancellableToolRunRegistry.consumeRecentlyCancelled(toolUseId)) {
                    StepEventBridge.emit(bridgeId, session -> {
                        session.recordToolCompleted(toolCatalogService.displayName(toolName));
                        session.noteToolCallDone();
                    });
                    StepEventBridge.unbindToolUseBridge(toolUseId);
                    return Mono.just(event);
                }
                Map<String, Object> enriched = SandboxStepContext.enrichInput(toolName, input, raw);
                summaryLine = sandboxTimelineLabels.after(
                        toolName, toolCatalogService.displayName(toolName), enriched);
                StepMetadata sandboxMeta = SandboxStepContext.metadata(toolName, enriched, summaryLine);
                // 写/编辑：展开入参正文；exec 完整 command 在 after，detail 仅工具输出
                if (SandboxIds.WRITE.equals(toolName) || SandboxIds.EDIT.equals(toolName)) {
                    String bodyExpand = HitlParamSupport.expandBodyFromParams(toStringParams(input));
                    expandDetail = StringUtils.hasText(bodyExpand)
                            ? bodyExpand
                            : (!raw.isEmpty() ? raw : null);
                } else {
                    expandDetail = !raw.isEmpty() ? raw : null;
                }
                final StepMetadata meta = sandboxMeta;
                StepEventBridge.emit(bridgeId, session -> {
                    session.completeToolStepForToolUse(toolUseId, summaryLine, expandDetail, meta);
                    session.recordToolCompleted(toolCatalogService.displayName(toolName));
                    session.noteToolCallDone();
                });
                StepEventBridge.unbindToolUseBridge(toolUseId);
                return Mono.just(event);
            } else {
                summaryLine = toolCatalogService.timelineSummary(toolName, rawText);
                expandDetail = ToolExpandDetailSupport.resolveExpandDetail(summaryLine, rawText);
            }
            StepEventBridge.emit(bridgeId, session -> {
                session.completeToolStepForToolUse(toolUseId, summaryLine, expandDetail);
                session.recordToolCompleted(toolCatalogService.displayName(toolName));
                session.noteToolCallDone();
            });
            StepEventBridge.unbindToolUseBridge(toolUseId);
            return Mono.just(event);
        }

        return Mono.just(event);
    }

    private String sandboxActiveSummary(String toolName, Map<String, Object> input) {
        if (!sandboxTimelineLabels.isSandboxTool(toolName)) {
            return null;
        }
        return sandboxTimelineLabels.active(toolName, toolCatalogService.displayName(toolName), input);
    }

    private static Map<String, Object> toolInput(ToolUseBlock toolUse) {
        if (toolUse == null || toolUse.getInput() == null) {
            return Map.of();
        }
        return toolUse.getInput();
    }

    private static Map<String, String> toStringParams(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : input.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            out.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return out;
    }

    private boolean isTaskBoardEnabled() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        return react != null && react.getTaskboard() != null && react.getTaskboard().isEnabled();
    }

    private String extractToolResultText(ToolResultBlock result) {
        if (result == null || result.getOutput() == null) {
            return "";
        }
        return result.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .findFirst()
                .orElse("");
    }
}
