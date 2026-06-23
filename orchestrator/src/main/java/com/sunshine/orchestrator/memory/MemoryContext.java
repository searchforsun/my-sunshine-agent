package com.sunshine.orchestrator.memory;

import com.sunshine.orchestrator.conversation.ChatTurn;

import java.util.List;

/**
 * 方案 C：LTM/MTM 为摘要 system 块；STM 为完整 user/assistant 轮次（窗口内不截断单条正文）。
 */
public record MemoryContext(
        String ltmSnippet,
        String mtmSnippet,
        List<ChatTurn> stmTurns
) {
    public static MemoryContext empty() {
        return new MemoryContext("", "", List.of());
    }

    /** 子 Agent：不携带 LTM/MTM/STM；上游材料经 AgentRunRequest.injectedBlocks 注入 */
    public static MemoryContext forSubAgent() {
        return empty();
    }

    public boolean hasAnyLayer() {
        return hasText(ltmSnippet) || hasText(mtmSnippet) || (stmTurns != null && !stmTurns.isEmpty());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
