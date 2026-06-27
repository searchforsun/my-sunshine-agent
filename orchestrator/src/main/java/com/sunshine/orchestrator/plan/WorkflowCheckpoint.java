package com.sunshine.orchestrator.plan;

/** Plan 暂停续跑检查点 */
public record WorkflowCheckpoint(
        String resumeNodeId,
        String wfCtxJson,
        PausePhase pausePhase,
        PendingInteraction pendingInteraction) {
    /** 兼容旧调用：默认 EXECUTING、无 pendingInteraction */
    public WorkflowCheckpoint(String resumeNodeId, String wfCtxJson) {
        this(resumeNodeId, wfCtxJson, PausePhase.EXECUTING, null);
    }
}
