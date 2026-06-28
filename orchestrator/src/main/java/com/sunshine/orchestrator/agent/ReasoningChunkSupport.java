package com.sunshine.orchestrator.agent;

import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;

/**
 * 从 AgentScope {@link ReasoningChunkEvent#getIncrementalChunk()} 提取增量。
 * ThinkingBlock → think step_delta；TextBlock → 正文段（与 reasoning 分离）。
 */
public final class ReasoningChunkSupport {

    private ReasoningChunkSupport() {
    }

    public static String extractIncrementalText(ReasoningChunkEvent event) {
        if (event == null) {
            return "";
        }
        return extractThinkingOnly(event.getIncrementalChunk());
    }

    /** ReasoningChunk 中 TextBlock 纯正文增量（LLM content token） */
    public static String extractIncrementalContent(ReasoningChunkEvent event) {
        if (event == null) {
            return "";
        }
        return extractTextBlockOnly(event.getIncrementalChunk());
    }

    public static String extractIncrementalText(Msg chunk) {
        return extractThinkingOnly(chunk);
    }

    private static String extractThinkingOnly(Msg msg) {
        if (msg == null) {
            return "";
        }
        for (ThinkingBlock block : msg.getContentBlocks(ThinkingBlock.class)) {
            String thinking = block.getThinking();
            if (thinking != null && !thinking.isEmpty()) {
                return thinking;
            }
        }
        return "";
    }

    static String extractTextBlockOnly(Msg msg) {
        if (msg == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (TextBlock block : msg.getContentBlocks(TextBlock.class)) {
            String text = block.getText();
            if (text != null && !text.isEmpty()) {
                sb.append(text);
            }
        }
        return sb.toString();
    }
}
