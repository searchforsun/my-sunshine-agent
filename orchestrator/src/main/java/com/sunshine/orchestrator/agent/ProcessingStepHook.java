package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ToolExpandDetailSupport;
import com.sunshine.orchestrator.taskboard.TaskBoardTimelineSupport;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import reactor.core.publisher.Mono;

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

    ProcessingStepHook(
            String bridgeId,
            ToolCatalogService toolCatalogService,
            AgentExecutionProperties executionProperties,
            TaskBoardTimelineSupport taskBoardTimelineSupport) {
        this.bridgeId = bridgeId;
        this.toolCatalogService = toolCatalogService;
        this.executionProperties = executionProperties;
        this.taskBoardTimelineSupport = taskBoardTimelineSupport;
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
            if (ManageTasksTool.NAME.equals(toolName)) {
                return Mono.just(event);
            }
            String toolUseId = pre.getToolUse().getId();
            StepEventBridge.bindToolUseBridge(toolUseId, bridgeId);
            String baseStepId = toolCatalogService.timelineStepId(toolName);
            String phase = toolCatalogService.timelinePhase(toolName);
            final String[] stepHolder = new String[1];
            StepEventBridge.emit(bridgeId, session -> {
                session.noteToolCallPending();
                stepHolder[0] = session.beginToolStep(baseStepId, phase);
            });
            if (stepHolder[0] != null) {
                StepEventBridge.bindToolUseStep(toolUseId, stepHolder[0]);
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
            String rawText = extractToolResultText(post.getToolResult());
            final String summaryLine;
            final String expandDetail;
            if (toolCatalogService.isRagTool(toolName)) {
                // search_knowledge 无 catalog 摘要模板；metadata/after 须从工具原始命中正文解析
                summaryLine = rawText;
                expandDetail = ToolExpandDetailSupport.resolveExpandDetail(null, rawText);
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
