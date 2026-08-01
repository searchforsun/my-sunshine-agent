package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;
import java.util.List;

/** L1 Mid/Near 轮次 + L2/Far/L3 system 块。SUB/PLANNER 用 empty/forSubAgent。 */
public record AssembledContext(
        String l2SystemBlock,
        String farSummaryBlock,
        List<ChatTurn> midTurns,
        List<ChatTurn> nearTurns,
        String l3MaterialBlock,
        String projectGuideBlock
) {
    public static AssembledContext empty() {
        return new AssembledContext("", "", List.of(), List.of(), "", "");
    }

    public static AssembledContext forSubAgent() {
        return empty();
    }

    /** 5 参便捷构造：无项目规范块（测试/直连路径用）。 */
    public AssembledContext(
            String l2SystemBlock,
            String farSummaryBlock,
            List<ChatTurn> midTurns,
            List<ChatTurn> nearTurns,
            String l3MaterialBlock) {
        this(l2SystemBlock, farSummaryBlock, midTurns, nearTurns, l3MaterialBlock, "");
    }

    public boolean hasAnyLayer() {
        return hasText(l2SystemBlock) || hasText(farSummaryBlock) || hasText(l3MaterialBlock)
                || hasText(projectGuideBlock)
                || (midTurns != null && !midTurns.isEmpty())
                || (nearTurns != null && !nearTurns.isEmpty());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
