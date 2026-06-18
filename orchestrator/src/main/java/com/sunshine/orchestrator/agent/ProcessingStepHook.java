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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * AgentScope Hook — ReAct 轮次边界与 reasoning 增量（唯一触达源）
 * <ul>
 *   <li>PreReasoning / PostReasoning：think 开闭</li>
 *   <li>ReasoningChunkEvent：think step_delta（原生 incrementalChunk）</li>
 *   <li>PreActing / PostActing：工具步骤</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ProcessingStepHook implements Hook {

    private final ToolCatalogService toolCatalogService;

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof io.agentscope.core.hook.PreReasoningEvent) {
            StepEventBridge.emitSingleton(ProcessingTimelineSession::beginReasoningRound);
            return Mono.just(event);
        }

        if (event instanceof io.agentscope.core.hook.PostReasoningEvent) {
            StepEventBridge.emitSingleton(ProcessingTimelineSession::endReasoningRound);
            return Mono.just(event);
        }

        if (event instanceof ReasoningChunkEvent chunkEvent) {
            String delta = ReasoningChunkSupport.extractIncrementalText(chunkEvent.getIncrementalChunk());
            StepEventBridge.emitSingletonReasoningChunk(delta);
            return Mono.just(event);
        }

        if (event instanceof PreActingEvent pre) {
            String toolName = pre.getToolUse().getName();
            String baseStepId = toolCatalogService.timelineStepId(toolName);
            String phase = toolCatalogService.timelinePhase(toolName);
            StepEventBridge.emitSingleton(session -> {
                session.noteToolCallPending();
                session.beginToolStep(baseStepId, phase);
            });
            return Mono.just(event);
        }

        if (event instanceof PostActingEvent post) {
            String toolName = post.getToolUse().getName();
            String detail = summarizeToolResult(toolName, post.getToolResult());
            StepEventBridge.emitSingleton(session -> {
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
