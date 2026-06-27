package com.sunshine.orchestrator.plan;

/** 续跑时恢复 HITL/Recovery 待交互态，跳过节点首次 run */
public record ResumeInteractionHint(PendingInteraction pending) {
}
