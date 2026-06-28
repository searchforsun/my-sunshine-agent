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
 * ReAct 正文：{@link EventType#REASONING} 增量帧与 {@link EventType#AGENT_RESULT} 终态快照
 * 均经 {@link com.sunshine.orchestrator.processing.ContentSegmentCoordinator} 单调 diff；
 * 重复整段由段内 baseline 规则丢弃，禁止旁路直灌。
 */
public final class AgentScopeEventMapper {

    private AgentScopeEventMapper() {
    }

    public static List<StreamToken> map(
            Event event,
            ProcessingTimelineSession session,
            AtomicBoolean answerContentStarted) {

        List<StreamToken> out = new ArrayList<>();
        EventType type = event.getType();
        if (type == EventType.REASONING) {
            // TextBlock 增量由 ReasoningChunkEvent Hook 即时刷 SSE，避免与 agent.stream 双写
            return out;
        }
        if (type != EventType.AGENT_RESULT) {
            return out;
        }

        Msg msg = event.getMessage();
        if (msg == null || !hasAnswerContent(msg)) {
            return out;
        }
        return emitAnswerContent(msg, session, answerContentStarted, out, type);
    }

    private static List<StreamToken> emitAnswerContent(
            Msg msg,
            ProcessingTimelineSession session,
            AtomicBoolean answerContentStarted,
            List<StreamToken> out,
            EventType type) {
        out.addAll(ProcessingTimelineSupport.run(session, session::completeThinkIfRunning));
        String anchor = session.contentAnchorAfterStepId();
        String cumulative = extractCumulativeText(msg);
        String baseline = session.contentSegmentBaseline();
        // Hook 已流式写完：终态快照与 baseline 前缀不一致时跳过，避免空格/格式差异导致整段复读
        if (!baseline.isEmpty() && !cumulative.startsWith(baseline)) {
            out.addAll(session.drainAuxiliaryTokens());
            answerContentStarted.set(true);
            return out;
        }
        session.contentSegments().ingest(cumulative, anchor, session::enqueueAuxiliary);
        out.addAll(session.drainAuxiliaryTokens());
        answerContentStarted.set(true);
        return out;
    }

    private static boolean hasAnswerContent(Msg msg) {
        if (!msg.getContentBlocks(TextBlock.class).isEmpty()) {
            return true;
        }
        String text = msg.getTextContent();
        return text != null && !text.isEmpty();
    }

    static String extractCumulativeText(Msg msg) {
        StringBuilder sb = new StringBuilder();
        for (TextBlock block : msg.getContentBlocks(TextBlock.class)) {
            String text = block.getText();
            if (text != null && !text.isEmpty()) {
                sb.append(text);
            }
        }
        if (sb.isEmpty()) {
            String fallback = msg.getTextContent();
            if (fallback != null) {
                sb.append(fallback);
            }
        }
        return sb.toString();
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
