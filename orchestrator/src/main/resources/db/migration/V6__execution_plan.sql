-- 执行计划字段：mode + workflowId（intent 列保留 intentLabel 兼容）
ALTER TABLE chat_message
    ADD COLUMN execution_mode VARCHAR(16) NULL AFTER intent,
    ADD COLUMN workflow_id   VARCHAR(64) NULL AFTER execution_mode;
