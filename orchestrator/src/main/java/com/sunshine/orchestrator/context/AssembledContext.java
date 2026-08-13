package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;
import java.util.List;

/** L1 Mid/Near 轮次 + L2/Far/L3 system 块。SUB 用 empty/forSubAgent；PLANNER 复用 assemble + H1 injectedBlocks；WORKER 用 forWorker。 */
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

    /**
     * Worker 上下文：l2/far/mid/near/l3 全空；稳定前缀进 projectGuideBlock（同 plan run 字节不变）。
     * {@code dynamicQueryBlock} 保留 API 稳定；动态 upstream 现由调用方拼进 query / injectedBlocks。
     */
    public static AssembledContext forWorker(String stablePrefixBlock, String dynamicQueryBlock) {
        String guide = stablePrefixBlock != null ? stablePrefixBlock : "";
        return new AssembledContext("", "", List.of(), List.of(), "", guide);
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
