package com.sunshine.orchestrator.agent.runtime;

/** 主/子/Planner/Worker 角色 */
public enum AgentRole {
    /** 顶层 ReAct，绑定 assistantMsgId，全量 Timeline */
    MAIN,
    /** Workflow 子节点，压缩 Timeline，不写回主 reasoning */
    SUB,
    /** 规划者，输出 Plan JSON（3.10.4 实现）；4.14 由 PlannerAgentRuntime / Facade 路由 */
    PLANNER,
    /** Planner-Executor Worker：工具白名单 + forWorker 稳定前缀，嵌套 Timeline */
    WORKER
}
