package com.sunshine.orchestrator.agent.runtime;

/** Timeline 绑定策略（Task 3.10.1） */
public enum TimelineBinding {
    /** 主 Agent：think / tool / generate 全量 */
    MAIN_FULL,
    /** 子 Agent：独立 bridge，主 Timeline 仅 node-{id} 一步 */
    SUB_COMPRESSED,
    /** Planner：harness ReAct 时间线（非一次性 plan 步） */
    PLANNER_ONLY,
    /** Worker：嵌套于 Planner 时间线（worker-* 行 + subSteps；与 SUB_COMPRESSED 区分便于前端） */
    WORKER_NESTED
}
