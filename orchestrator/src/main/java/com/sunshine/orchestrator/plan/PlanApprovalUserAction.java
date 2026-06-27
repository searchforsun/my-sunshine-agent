package com.sunshine.orchestrator.plan;

/** Plan 用户确认等待结果 */
public enum PlanApprovalUserAction {
    APPROVED,
    REGENERATED,
    TIMED_OUT,
    /** 用户暂停 generation，须落库 checkpoint 后退出 */
    CANCELLED
}
