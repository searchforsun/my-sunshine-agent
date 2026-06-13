package com.sunshine.orchestrator.agent;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AgentScope Hook — 工具调用步骤（跨线程，经 StepEventBridge 写入 TimelineSession）
 */
@Component
public class ProcessingStepHook implements Hook {

    private static final Pattern HIT_COUNT = Pattern.compile("共\\s*(\\d+)\\s*条");

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
        if (text.isBlank() || text.contains("未找到")) {
            return "命中 0 条";
        }
        Matcher matcher = HIT_COUNT.matcher(text);
        if (matcher.find()) {
            return "命中 " + matcher.group(1) + " 条";
        }
        return "命中 0 条";
    }
}
