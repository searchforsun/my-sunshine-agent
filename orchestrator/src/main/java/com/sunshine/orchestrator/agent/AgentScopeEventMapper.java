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
 * think 轮 ThinkingBlock 由 Hook {@link io.agentscope.core.hook.ReasoningChunkEvent} 产出；
 * 正文增量由 {@code incremental=true} 下 REASONING/AGENT_RESULT 的 TextBlock 承载，
 * 累积全文经 {@link com.sunshine.orchestrator.client.StreamDeltaNormalizer} 转为真增量。
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
        EventType type = event.getType();
        if (type != EventType.REASONING && type != EventType.AGENT_RESULT) {
            return out;
        }

        Msg msg = event.getMessage();
        if (msg == null || !hasAnswerContent(msg)) {
            return out;
        }
        return emitAnswerContent(msg, session, generateStarted, out);
    }

    private static List<StreamToken> emitAnswerContent(
            Msg msg,
            ProcessingTimelineSession session,
            AtomicBoolean generateStarted,
            List<StreamToken> out) {
        long tokenAt = System.currentTimeMillis();
        if (!generateStarted.get()) {
            out.addAll(ProcessingTimelineSupport.run(session, () -> {
                session.completeThinkIfRunning();
                session.pending(GENERATE, GENERATE);
                session.startAt(GENERATE, GENERATE, tokenAt);
            }));
            generateStarted.set(true);
        } else {
            // 多轮 ReAct：第二轮及以后正文前须关闭当前 think，避免 generateStarted 已置位时漏关 think-2
            out.addAll(ProcessingTimelineSupport.run(session, session::completeThinkIfRunning));
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

    /** 每帧携带 AgentScope 累积正文；增量由 StreamDeltaNormalizer 提取 */
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
