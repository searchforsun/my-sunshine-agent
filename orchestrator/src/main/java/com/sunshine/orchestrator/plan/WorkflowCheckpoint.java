package com.sunshine.orchestrator.plan;

/** Plan 暂停续跑检查点 */
public record WorkflowCheckpoint(String resumeNodeId, String wfCtxJson) {
}
