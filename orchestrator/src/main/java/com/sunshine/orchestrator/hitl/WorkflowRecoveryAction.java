package com.sunshine.orchestrator.hitl;

/** 用户对工作流失败节点的处置 */
public enum WorkflowRecoveryAction {
    RETRY,
    SKIP,
    TERMINATE,
    TIMEOUT
}
