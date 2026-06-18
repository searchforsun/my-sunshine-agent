package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.catalog.ToolCatalogService;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * AgentScope Hook — 推理/工具生命周期步骤（经 StepEventBridge 异步写入 TimelineSession）
 * <ul>
 *   <li>PreReasoning / PostReasoning：think 开闭（轮次边界）</li>
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

        if (event instanceof PreActingEvent pre) {
            String toolName = pre.getToolUse().getName();
            String stepId = toolCatalogService.timelineStepId(toolName);
            String phase = toolCatalogService.timelinePhase(toolName);
            StepEventBridge.emitSingleton(session -> {
                session.noteToolCallPending();
                if (!session.hasStep(stepId)) {
                    session.pending(stepId, phase);
                    session.start(stepId, phase);
                }
            });
            return Mono.just(event);
        }

        if (event instanceof PostActingEvent post) {
            String toolName = post.getToolUse().getName();
            String stepId = toolCatalogService.timelineStepId(toolName);
            String phase = toolCatalogService.timelinePhase(toolName);
            String detail = summarizeToolResult(toolName, post.getToolResult());
            StepEventBridge.emitSingleton(session -> {
                if (!session.hasStep(stepId)) {
                    session.pending(stepId, phase);
                    session.start(stepId, phase);
                }
                session.complete(stepId, detail != null ? detail : "命中 0 条");
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
