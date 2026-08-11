package com.sunshine.orchestrator.agent;

import java.util.List;

/**
 * ReAct 续跑决策准备结果。
 * <ul>
 *   <li>{@link #none()} — 无需处理，继续 streamEvents</li>
 *   <li>{@link #resolved(List)} — 用户已选；injectBlocks 须并入 Prompt 注入（不依赖二次 tool_call）</li>
 *   <li>{@link #aborted()} — 续跑 re-await 超时/取消/注册失败；对齐 HITL {@code HitlWaitInterruptedException}，中止本次 run</li>
 * </ul>
 */
public record DecisionResumeOutcome(boolean shouldAbort, List<String> injectBlocks) {

    public DecisionResumeOutcome {
        injectBlocks = injectBlocks != null ? List.copyOf(injectBlocks) : List.of();
    }

    public static DecisionResumeOutcome none() {
        return new DecisionResumeOutcome(false, List.of());
    }

    public static DecisionResumeOutcome resolved(List<String> injectBlocks) {
        return new DecisionResumeOutcome(false, injectBlocks);
    }

    public static DecisionResumeOutcome aborted() {
        return new DecisionResumeOutcome(true, List.of());
    }
}
