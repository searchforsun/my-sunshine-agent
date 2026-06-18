package com.sunshine.orchestrator.agent;

import com.sunshine.orchestrator.client.StreamToken;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.ProcessingTimelineSupport;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将 AgentScope {@link Event} 映射为 {@link StreamToken}。
 * ReAct reasoning 由 Hook {@link ReasoningChunkEvent} 产出；此处仅处理 AGENT_RESULT 正文。
 */
public final class AgentScopeEventMapper {

    private static final String GENERATE = "generate";

    private AgentScopeEventMapper() {
    }

    public static List<StreamToken> map(
            Event event,
            ProcessingTimelineSession session,
            AtomicBoolean generateStarted) {

        List<StreamToken> out = new ArrayList<>();
        if (event.getType() != EventType.AGENT_RESULT) {
            return out;
        }

        Msg msg = event.getMessage();
        if (msg == null || !hasAnswerContent(msg)) {
            return out;
        }

        if (generateStarted.compareAndSet(false, true)) {
            long firstTokenAt = System.currentTimeMillis();
            out.addAll(ProcessingTimelineSupport.run(session, () -> {
                session.completeThinkIfRunning();
                session.pending(GENERATE, GENERATE);
                session.startAt(GENERATE, GENERATE, firstTokenAt);
            }));
        }
        appendContentTokens(msg, out);
        return out;
    }

    private static boolean hasAnswerContent(Msg msg) {
        if (!msg.getContentBlocks(TextBlock.class).isEmpty()) {
            return true;
        }
        String text = msg.getTextContent();
        return text != null && !text.isEmpty();
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

    static String summarizeHits(ToolResultBlock block) {
        return ToolResultSummarizer.summarize("search_knowledge", extractToolResultText(block));
    }

    private static String extractToolResultText(ToolResultBlock block) {
        if (block == null || block.getOutput() == null) {
            return "";
        }
        return block.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .findFirst()
                .orElse("");
    }
}
