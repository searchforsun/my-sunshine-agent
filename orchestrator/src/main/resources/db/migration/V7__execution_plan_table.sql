-- 动态 Plan 持久化（阶段三 3.9.2）
CREATE TABLE execution_plan (
    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    conversation_id  VARCHAR(64)  NOT NULL,
    message_id       VARCHAR(64)  NOT NULL,
    user_id          VARCHAR(64)  NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL DEFAULT 'default',
    status           VARCHAR(24)  NOT NULL,
    planner_model    VARCHAR(64)  NULL,
    planner_reason   VARCHAR(512) NULL,
    plan_json        MEDIUMTEXT   NOT NULL,
    validated_json   MEDIUMTEXT   NULL,
    execution_trace  MEDIUMTEXT   NULL,
    trace_id         VARCHAR(64)  NULL,
    reject_reason    VARCHAR(512) NULL,
    created_at       DATETIME(3)  NOT NULL,
    validated_at     DATETIME(3)  NULL,
    started_at       DATETIME(3)  NULL,
    completed_at     DATETIME(3)  NULL,
    INDEX idx_ep_conv (conversation_id),
    INDEX idx_ep_msg (message_id)
);

ALTER TABLE chat_message
    ADD COLUMN execution_plan_id VARCHAR(36) NULL AFTER workflow_id,
    ADD INDEX idx_msg_execution_plan (execution_plan_id);
