package com.sunshine.orchestrator.context;

import com.sunshine.orchestrator.conversation.ChatTurn;
import java.util.List;
import java.util.Set;

/** L1 Mid/Near 轮次 + L2/Far/L3 system 块 + 任务板恢复块。SUB 用 empty/forSubAgent；PLANNER 复用 assemble + H1 injectedBlocks；WORKER 用 forWorker。 */
public record AssembledContext(
        String l2SystemBlock,
        String farSummaryBlock,
        List<ChatTurn> midTurns,
        List<ChatTurn> nearTurns,
        String l3MaterialBlock,
        String projectGuideBlock,
        String taskListRestoreBlock,
        /** L3 延后装配（authority §2.2 方案 A）锚点：路由后按分区排除 ID 召回 L3；非渲染元数据 */
        L3Anchor l3Anchor
) {
    /** L3 召回锚点（M0）：由 assemble 在分区后挂载，attachL3 路由后消费。 */
    public record L3Anchor(Set<String> excludeMsgIds, Set<String> farMsgIds, boolean farSummaryNonEmpty) {
        public static final L3Anchor EMPTY = new L3Anchor(Set.of(), Set.of(), false);
    }

    public static AssembledContext empty() {
        return new AssembledContext("", "", List.of(), List.of(), "", "", "", L3Anchor.EMPTY);
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
        return new AssembledContext("", "", List.of(), List.of(), "", guide, "", L3Anchor.EMPTY);
    }

    /** 5 参便捷构造：无项目规范块、无任务板恢复块、无 L3 锚点（测试/直连路径用）。 */
    public AssembledContext(
            String l2SystemBlock,
            String farSummaryBlock,
            List<ChatTurn> midTurns,
            List<ChatTurn> nearTurns,
            String l3MaterialBlock) {
        this(l2SystemBlock, farSummaryBlock, midTurns, nearTurns, l3MaterialBlock, "", "");
    }

    /** 7 参便捷构造：无 L3 锚点（既有调用兼容，M0 前行为）。 */
    public AssembledContext(
            String l2SystemBlock,
            String farSummaryBlock,
            List<ChatTurn> midTurns,
            List<ChatTurn> nearTurns,
            String l3MaterialBlock,
            String projectGuideBlock,
            String taskListRestoreBlock) {
        this(l2SystemBlock, farSummaryBlock, midTurns, nearTurns, l3MaterialBlock,
                projectGuideBlock, taskListRestoreBlock, L3Anchor.EMPTY);
    }

    /** 任务板恢复块：跨轮复用任务板，render 顺序位于 Near 之后、L3 之前。 */
    public AssembledContext withTaskListRestoreBlock(String block) {
        return new AssembledContext(l2SystemBlock, farSummaryBlock, midTurns, nearTurns,
                l3MaterialBlock, projectGuideBlock, block == null ? "" : block, l3Anchor);
    }

    /** L3 材料块替换：冲突仲裁（authority §5.2）过滤后的摘要；null/空按空串归一。 */
    public AssembledContext withL3MaterialBlock(String block) {
        return new AssembledContext(l2SystemBlock, farSummaryBlock, midTurns, nearTurns,
                block == null ? "" : block, projectGuideBlock, taskListRestoreBlock, l3Anchor);
    }

    /** L3 延后装配锚点挂载（M0）：保留 anchor 字段供路由后 attachL3 消费。 */
    public AssembledContext withL3Anchor(L3Anchor anchor) {
        return new AssembledContext(l2SystemBlock, farSummaryBlock, midTurns, nearTurns,
                l3MaterialBlock, projectGuideBlock, taskListRestoreBlock,
                anchor != null ? anchor : L3Anchor.EMPTY);
    }

    public boolean hasAnyLayer() {
        return hasText(l2SystemBlock) || hasText(farSummaryBlock) || hasText(l3MaterialBlock)
                || hasText(projectGuideBlock) || hasText(taskListRestoreBlock)
                || (midTurns != null && !midTurns.isEmpty())
                || (nearTurns != null && !nearTurns.isEmpty());
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
