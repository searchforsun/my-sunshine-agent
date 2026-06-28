-- Sunshine 业务库：sunshine_chat
USE sunshine_chat;

-- V1__chat_schema.sql
CREATE TABLE chat_conversation (
    id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'default',
    title       VARCHAR(128) NOT NULL DEFAULT '新对话',
    created_at  DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    INDEX idx_user_tenant_updated (user_id, tenant_id, updated_at)
);

CREATE TABLE chat_message (
    id               VARCHAR(64)  NOT NULL PRIMARY KEY,
    conversation_id  VARCHAR(64)  NOT NULL,
    seq              INT          NOT NULL,
    role             VARCHAR(16)  NOT NULL,
    content          MEDIUMTEXT   NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'completed',
    intent           VARCHAR(32)  NULL,
    resume_count     INT          NOT NULL DEFAULT 0,
    created_at       DATETIME(3)  NOT NULL,
    updated_at       DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_conv_seq (conversation_id, seq),
    INDEX idx_conv_created (conversation_id, created_at),
    CONSTRAINT fk_msg_conv FOREIGN KEY (conversation_id) REFERENCES chat_conversation(id) ON DELETE CASCADE
);

-- V2__add_message_reasoning.sql
ALTER TABLE chat_message
    ADD COLUMN reasoning MEDIUMTEXT NULL AFTER content;

-- V3__add_message_steps.sql
ALTER TABLE chat_message
    ADD COLUMN steps MEDIUMTEXT NULL AFTER reasoning;

-- V4__chat_audit_log.sql
CREATE TABLE chat_audit_log (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(64)  NOT NULL,
    message_id      VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'default',
    event_type      VARCHAR(32)  NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    intent          VARCHAR(32)  NULL,
    content_len     INT          NOT NULL DEFAULT 0,
    payload         JSON         NULL,
    created_at      DATETIME(3)  NOT NULL,
    INDEX idx_audit_user_time (user_id, created_at),
    INDEX idx_audit_msg (message_id)
);

-- V5__memory_schema.sql
-- STM/MTM/LTM 三层记忆 — 中期摘要与长期画像（STM 热数据在 Redis）

CREATE TABLE conversation_memory_mtm (
    id          VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL,
    tenant_id   VARCHAR(32)  NOT NULL DEFAULT 'default',
    conv_id     VARCHAR(32)  NOT NULL,
    summary     TEXT         NOT NULL,
    topics      VARCHAR(512) NULL,
    intent      VARCHAR(32)  NULL,
    heat_score  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP(3) NOT NULL,
    updated_at  TIMESTAMP(3) NOT NULL,
    UNIQUE KEY uk_mtm_conv (conv_id),
    INDEX idx_mtm_user_time (user_id, tenant_id, created_at DESC)
);

CREATE TABLE user_memory_profile (
    id            VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_id       VARCHAR(64)  NOT NULL,
    tenant_id     VARCHAR(32)  NOT NULL DEFAULT 'default',
    department    VARCHAR(128) NULL,
    role_label    VARCHAR(128) NULL,
    preferences   TEXT         NULL,
    stable_facts  TEXT         NULL,
    permissions   VARCHAR(512) NULL,
    created_at    TIMESTAMP(3) NOT NULL,
    updated_at    TIMESTAMP(3) NOT NULL,
    UNIQUE KEY uk_profile_user (user_id, tenant_id)
);

-- V6__execution_plan.sql
-- 执行计划字段：mode + workflowId（intent 列保留 intentLabel 兼容）
ALTER TABLE chat_message
    ADD COLUMN execution_mode VARCHAR(16) NULL AFTER intent,
    ADD COLUMN workflow_id   VARCHAR(64) NULL AFTER execution_mode;

-- V7__execution_plan_table.sql
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

-- V8__execution_plan_retry.sql
-- Plan 重试审计：Planner attempt 记录
ALTER TABLE execution_plan
    ADD COLUMN planner_attempts MEDIUMTEXT NULL AFTER plan_json,
    ADD COLUMN replan_count INT NOT NULL DEFAULT 0 AFTER planner_attempts;

-- V9__conversation_execution_preference.sql
ALTER TABLE chat_conversation
    ADD COLUMN execution_preference VARCHAR(32) NULL COMMENT 'auto|simple-llm|react|workflow|plan-workflow';

-- V10__message_execution_preference.sql
ALTER TABLE chat_message
    ADD COLUMN execution_preference VARCHAR(32) NULL COMMENT 'user 消息发送时 executionPreference';

-- V11__execution_plan_pause_checkpoint.sql
-- Plan-Workflow 暂停续跑检查点
ALTER TABLE execution_plan
    ADD COLUMN pause_checkpoint MEDIUMTEXT NULL COMMENT '暂停续跑 JSON：resumeNodeId + wfCtx' AFTER execution_trace;

-- V13__message_content_blocks.sql
ALTER TABLE chat_message
    ADD COLUMN content_blocks MEDIUMTEXT NULL COMMENT 'ReAct 正文分段 JSON' AFTER steps;

-- Flyway 基线（与 classpath db/migration 校验一致，避免服务启动重复建表）
USE sunshine_chat;
CREATE TABLE IF NOT EXISTS flyway_schema_history (
    installed_rank INT NOT NULL,
    version VARCHAR(50),
    description VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL,
    script VARCHAR(1000) NOT NULL,
    checksum INT,
    installed_by VARCHAR(100) NOT NULL,
    installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    execution_time INT NOT NULL,
    success TINYINT(1) NOT NULL,
    PRIMARY KEY (installed_rank),
    INDEX flyway_schema_history_s_idx (success)
);
DELETE FROM flyway_schema_history;
INSERT INTO flyway_schema_history VALUES (1, '1', 'chat schema', 'SQL', 'V1__chat_schema.sql', -12532465, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (2, '2', 'add message reasoning', 'SQL', 'V2__add_message_reasoning.sql', -1624359590, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (3, '3', 'add message steps', 'SQL', 'V3__add_message_steps.sql', -273411881, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (4, '4', 'chat audit log', 'SQL', 'V4__chat_audit_log.sql', 1426551964, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (5, '5', 'memory schema', 'SQL', 'V5__memory_schema.sql', 604968421, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (6, '6', 'execution plan', 'SQL', 'V6__execution_plan.sql', -442101636, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (7, '7', 'execution plan table', 'SQL', 'V7__execution_plan_table.sql', -1080577868, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (8, '8', 'execution plan retry', 'SQL', 'V8__execution_plan_retry.sql', 765852937, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (9, '9', 'conversation execution preference', 'SQL', 'V9__conversation_execution_preference.sql', -726680187, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (10, '10', 'message execution preference', 'SQL', 'V10__message_execution_preference.sql', -1848876767, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (11, '11', 'execution plan pause checkpoint', 'SQL', 'V11__execution_plan_pause_checkpoint.sql', -1181160291, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (12, '12', 'execution plan approval rounds', 'SQL', 'V12__execution_plan_approval_rounds.sql', 0, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (13, '13', 'message content blocks', 'SQL', 'V13__message_content_blocks.sql', 0, 'docker-init', NOW(), 0, 1);
