package com.sunshine.orchestrator.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * AgentScope Hook — 工具调用步骤（跨线程，经 StepEventBridge 写入 TimelineSession）
 */
@Component
public class ProcessingStepHook implements Hook {

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreActingEvent pre) {
            String toolName = pre.getToolUse().getName();
            if ("search_knowledge".equals(toolName)) {
                StepEventBridge.emitSingleton(session -> {
                    if (!session.hasStep("rag")) {
                        session.pending("rag", "rag");
                        session.start("rag", "rag");
                    }
                });
            } else {
                String stepId = "tool-" + toolName;
                StepEventBridge.emitSingleton(session -> {
                    if (!session.hasStep(stepId)) {
                        session.pending(stepId, "agent");
                        session.start(stepId, "agent");
                    }
                });
            }
            return Mono.just(event);
        }

        if (event instanceof PostActingEvent post) {
            String toolName = post.getToolUse().getName();
            String detail = summarizeToolResult(post.getToolResult());
            if ("search_knowledge".equals(toolName)) {
                StepEventBridge.emitSingleton(session -> {
                    if (!session.hasStep("rag")) {
                        session.pending("rag", "rag");
                        session.start("rag", "rag");
                    }
                    session.complete("rag", detail != null ? detail : "命中 0 条");
                });
            } else {
                String stepId = "tool-" + toolName;
                StepEventBridge.emitSingleton(session ->
                        session.complete(stepId, detail));
            }
            return Mono.just(event);
        }

        return Mono.just(event);
    }

    private static String summarizeToolResult(ToolResultBlock result) {
        if (result == null || result.getOutput() == null) {
            return "命中 0 条";
        }
        String text = result.getOutput().stream()
                .filter(block -> block instanceof TextBlock)
                .map(block -> ((TextBlock) block).getText())
                .findFirst()
                .orElse("");
        return RagHitSummarizer.summarize(text);
    }
}
