package com.sunshine.orchestrator.agent.runtime;

/** 主/子/Planner 三角色（Task 3.10.1） */
public enum AgentRole {
    /** 顶层 ReAct，绑定 assistantMsgId，全量 Timeline */
    MAIN,
    /** Workflow 子节点，压缩 Timeline，不写回主 reasoning */
    SUB,
    /** 规划者，输出 Plan JSON（3.10.4 实现） */
    PLANNER
}
