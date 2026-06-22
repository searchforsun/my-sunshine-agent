package com.sunshine.orchestrator.agent.runtime;

/** Timeline 绑定策略（Task 3.10.1） */
public enum TimelineBinding {
    /** 主 Agent：think / tool / generate 全量 */
    MAIN_FULL,
    /** 子 Agent：独立 bridge，主 Timeline 仅 node-{id} 一步 */
    SUB_COMPRESSED,
    /** Planner：仅 plan 步（3.10.4） */
    PLANNER_ONLY
}
