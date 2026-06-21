package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import reactor.core.publisher.Mono;

/**
 * AgentScope Hook — ReAct 轮次边界与 reasoning 增量（唯一触达源）
 * <ul>
 *   <li>PreReasoning / PostReasoning：think 开闭</li>
 *   <li>ReasoningChunkEvent：think step_delta（原生 incrementalChunk）</li>
 *   <li>PreActing / PostActing：工具步骤</li>
 * </ul>
 * 每个 ReAct 请求通过 {@link ProcessingStepHookFactory} 绑定独立 bridgeId，并发安全。
 */
public class ProcessingStepHook implements Hook {

    private final String bridgeId;
    private final ToolCatalogService toolCatalogService;

    ProcessingStepHook(String bridgeId, ToolCatalogService toolCatalogService) {
        this.bridgeId = bridgeId;
        this.toolCatalogService = toolCatalogService;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof io.agentscope.core.hook.PreReasoningEvent) {
            StepEventBridge.emit(bridgeId, ProcessingTimelineSession::beginReasoningRound);
            return Mono.just(event);
        }

        if (event instanceof io.agentscope.core.hook.PostReasoningEvent) {
            StepEventBridge.emit(bridgeId, ProcessingTimelineSession::endReasoningRound);
            return Mono.just(event);
        }

        if (event instanceof ReasoningChunkEvent chunkEvent) {
            String delta = ReasoningChunkSupport.extractIncrementalText(chunkEvent);
            StepEventBridge.emitReasoningChunk(bridgeId, delta);
            return Mono.just(event);
        }

        if (event instanceof PreActingEvent pre) {
            String toolName = pre.getToolUse().getName();
            String baseStepId = toolCatalogService.timelineStepId(toolName);
            String phase = toolCatalogService.timelinePhase(toolName);
            StepEventBridge.emit(bridgeId, session -> {
                session.noteToolCallPending();
                session.beginToolStep(baseStepId, phase);
            });
            return Mono.just(event);
        }

        if (event instanceof PostActingEvent post) {
            String toolName = post.getToolUse().getName();
            String detail = summarizeToolResult(toolName, post.getToolResult());
            StepEventBridge.emit(bridgeId, session -> {
                session.completeToolStep(detail != null ? detail : "命中 0 条");
                session.recordToolCompleted(toolCatalogService.displayName(toolName));
                session.noteToolCallDone();
            });
            return Mono.just(event);
        }

        return Mono.just(event);
    }

    private String summarizeToolResult(String toolName, ToolResultBlock result) {
        if (result == null || result.getOutput() == null) {
            return toolCatalogService.summarizeOutput(toolName, "");
        }
        String text = result.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .findFirst()
                .orElse("");
        return toolCatalogService.summarizeOutput(toolName, text);
    }
}
