package com.sunshine.orchestrator.agent;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;

/**
 * 从 AgentScope {@link io.agentscope.core.hook.ReasoningChunkEvent#getIncrementalChunk()} 提取推理增量文本。
 */
public final class ReasoningChunkSupport {

    private ReasoningChunkSupport() {
    }

    public static String extractIncrementalText(Msg chunk) {
        if (chunk == null) {
            return "";
        }
        for (ThinkingBlock block : chunk.getContentBlocks(ThinkingBlock.class)) {
            String thinking = block.getThinking();
            if (thinking != null && !thinking.isEmpty()) {
                return thinking;
            }
        }
        for (TextBlock block : chunk.getContentBlocks(TextBlock.class)) {
            String text = block.getText();
            if (text != null && !text.isEmpty()) {
                return text;
            }
        }
        String fallback = chunk.getTextContent();
        return fallback != null ? fallback : "";
    }
}
