-- Plan-Workflow 暂停续跑检查点
ALTER TABLE execution_plan
    ADD COLUMN pause_checkpoint MEDIUMTEXT NULL COMMENT '暂停续跑 JSON：resumeNodeId + wfCtx' AFTER execution_trace;
