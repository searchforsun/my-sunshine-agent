package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import com.sunshine.orchestrator.processing.StepSummarizer;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将 AgentScope {@link Event} 映射为 {@link StreamToken}，步骤直接内联到主流
 */
public final class AgentScopeEventMapper {

    private AgentScopeEventMapper() {
    }

    public static List<StreamToken> map(
            Event event,
            ProcessingTimelineSession session,
            AtomicBoolean agentCompleted,
            AtomicBoolean generateStarted,
            String ragDetailHint,
            String userQuery) {

        List<StreamToken> out = new ArrayList<>();
        EventType type = event.getType();
        Msg msg = event.getMessage();
        if (msg == null) {
            return out;
        }

        if (type == EventType.REASONING) {
            if (!agentCompleted.get()) {
                String progress = userQuery != null
                        ? StepSummarizer.agentProgress(userQuery)
                        : "正在深入分析问题与上下文";
                out.addAll(ProcessingTimelineSupport.run(session, () ->
                        session.progress("agent", progress)));
            }
            appendThinkingTokens(msg, out);
            return out;
        }

        if (type == EventType.TOOL_RESULT) {
            out.addAll(appendToolResultSteps(msg, session));
            return out;
        }

        if (type == EventType.AGENT_RESULT) {
            boolean hasContent = hasAnswerContent(msg);
            if (hasContent) {
                appendContentTokens(msg, out);
                if (generateStarted.compareAndSet(false, true)) {
                    long firstTokenAt = System.currentTimeMillis();
                    agentCompleted.set(true);
                    out.addAll(ProcessingTimelineSupport.run(session, () -> {
                        session.completeAt("agent", ragDetailHint, firstTokenAt);
                        session.pending("generate", "generate");
                        session.startAt("generate", "generate", firstTokenAt);
                    }));
                }
            } else if (event.isLast() && agentCompleted.compareAndSet(false, true)) {
                out.addAll(ProcessingTimelineSupport.run(session, () ->
                        session.complete("agent", ragDetailHint)));
            }
            return out;
        }

        if (type == EventType.HINT) {
            for (ToolUseBlock toolUse : msg.getContentBlocks(ToolUseBlock.class)) {
                if ("search_knowledge".equals(toolUse.getName())) {
                    out.addAll(ensureRagStarted(session));
                }
            }
            return out;
        }

        return out;
    }

    private static boolean hasAnswerContent(Msg msg) {
        if (!msg.getContentBlocks(TextBlock.class).isEmpty()) {
            return true;
        }
        String text = msg.getTextContent();
        return text != null && !text.isEmpty();
    }

    private static List<StreamToken> ensureRagStarted(ProcessingTimelineSession session) {
        if (session.hasStep("rag")) {
            return List.of();
        }
        return ProcessingTimelineSupport.run(session, () -> {
            session.pending("rag", "rag");
            session.start("rag", "rag");
        });
    }

    /** 仅 ThinkingBlock 进入 reasoning 通道；REASONING 事件中的 TextBlock 是成稿预览，不传输。 */
    static void appendThinkingTokens(Msg msg, List<StreamToken> out) {
        for (ThinkingBlock block : msg.getContentBlocks(ThinkingBlock.class)) {
            String thinking = block.getThinking();
            if (thinking != null && !thinking.isEmpty()) {
                out.add(StreamToken.reasoning(thinking));
            }
        }
    }

    static void appendContentTokens(Msg msg, List<StreamToken> out) {
        for (TextBlock block : msg.getContentBlocks(TextBlock.class)) {
            String text = block.getText();
            if (text != null && !text.isEmpty()) {
                out.add(StreamToken.content(text));
            }
        }
        String fallback = msg.getTextContent();
        if (out.stream().noneMatch(StreamToken::isContent) && fallback != null && !fallback.isEmpty()) {
            out.add(StreamToken.content(fallback));
        }
    }

    private static List<StreamToken> appendToolResultSteps(Msg msg, ProcessingTimelineSession session) {
        List<StreamToken> steps = new ArrayList<>();
        for (ToolResultBlock block : msg.getContentBlocks(ToolResultBlock.class)) {
            if ("search_knowledge".equals(block.getName())) {
                String detail = summarizeHits(block);
                steps.addAll(ProcessingTimelineSupport.run(session, () -> {
                    if (!session.hasStep("rag")) {
                        session.pending("rag", "rag");
                        session.start("rag", "rag");
                    }
                    session.complete("rag", detail);
                }));
            }
        }
        return steps;
    }

    static String summarizeHits(ToolResultBlock block) {
        if (block == null || block.getOutput() == null) {
            return "命中 0 条";
        }
        String text = block.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .findFirst()
                .orElse("");
        return RagHitSummarizer.summarize(text);
    }
}
