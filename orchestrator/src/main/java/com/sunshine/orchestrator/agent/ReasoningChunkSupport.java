package com.sunshine.orchestrator.agent;

import io.agentscope.core.hook.ReasoningChunkEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ThinkingBlock;

/**
 * 从 AgentScope {@link ReasoningChunkEvent#getIncrementalChunk()} 提取推理增量文本。
 * 仅读取 {@link ThinkingBlock}，禁止把 TextBlock（正文）误入 reasoning。
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
}
