-- sunshine-orchestrator（orchestrator :8200 · 库 sunshine_chat）
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

-- V5__context_schema.sql
-- 上下文优化：L1 派生 + L2 状态（废止 conversation_memory_mtm / user_memory_profile）
-- 已有库一次性迁移见 scripts/migrate_context_l1_l2.sql（禁止 Flyway）

CREATE TABLE conversation_context_l1 (
    conv_id              VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_id              VARCHAR(64)  NOT NULL,
    tenant_id            VARCHAR(32)  NOT NULL DEFAULT 'default',
    mid_answers          MEDIUMTEXT   NULL COMMENT 'JSON map msgId -> answer summary',
    far_summary          MEDIUMTEXT   NULL,
    far_folded_msg_ids   MEDIUMTEXT   NULL COMMENT 'JSON array of msgIds already folded into far_summary',
    near_n               INT          NOT NULL DEFAULT 8,
    mid_n                INT          NOT NULL DEFAULT 8,
    updated_at           TIMESTAMP(3) NOT NULL,
    INDEX idx_l1_user (user_id, tenant_id)
);

CREATE TABLE user_context_state (
    id              VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_id         VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'default',
    kind            VARCHAR(32)  NOT NULL,
    state_key       VARCHAR(128) NOT NULL,
    state_value     TEXT         NOT NULL,
    confidence      DOUBLE       NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    expires_at      TIMESTAMP(3) NULL,
    source_msg_id   VARCHAR(64)  NULL,
    created_at      TIMESTAMP(3) NOT NULL,
    updated_at      TIMESTAMP(3) NOT NULL,
    -- 无 UNIQUE(kind,key)：同 key 可多条 superseded 审计；应用保证至多一条 active
    INDEX idx_ctx_user_kind_key_status (user_id, tenant_id, kind, state_key, status),
    INDEX idx_ctx_user_status (user_id, tenant_id, status),
    INDEX idx_ctx_expires (expires_at)
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

-- V12__execution_plan_approval_rounds.sql
ALTER TABLE execution_plan
    ADD COLUMN approval_rounds MEDIUMTEXT NULL COMMENT 'Plan 用户确认轮次 JSON' AFTER planner_attempts;

-- V13__message_content_blocks.sql
ALTER TABLE chat_message
    ADD COLUMN content_blocks MEDIUMTEXT NULL COMMENT 'ReAct 正文分段 JSON' AFTER steps;

-- V14__message_react_pause_checkpoint.sql
ALTER TABLE chat_message
    ADD COLUMN react_pause_checkpoint MEDIUMTEXT NULL COMMENT 'ReAct 暂停续跑 JSON';

-- V15__conversation_kb_id.sql
ALTER TABLE chat_conversation
    ADD COLUMN kb_id VARCHAR(64) NULL COMMENT '会话绑定的知识库 id';

-- V16__react_task_board.sql
CREATE TABLE react_task_board (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    message_id      VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'default',
    user_id         VARCHAR(64)  NOT NULL,
    revision        INT          NOT NULL,
    items_json      JSON         NOT NULL,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_react_task_board_msg (message_id),
    INDEX idx_react_task_board_conv (conversation_id, updated_at)
);

-- V17__peer_run.sql
CREATE TABLE peer_run (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    message_id      VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'default',
    user_id         VARCHAR(64)  NOT NULL,
    template_id     VARCHAR(128) NOT NULL,
    transcript_json JSON         NOT NULL,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_peer_run_msg (message_id),
    INDEX idx_peer_run_conv (conversation_id, updated_at)
);

-- V18__agent_workspace.sql
CREATE TABLE agent_workspace (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    tenant_id       VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    repo_url        VARCHAR(512) NOT NULL,
    repo_branch     VARCHAR(128) NOT NULL DEFAULT 'main',
    sandbox_profile VARCHAR(32)  NOT NULL DEFAULT 'full',
    memory_mb       INT          NOT NULL DEFAULT 2048,
    cpus            DECIMAL(3,1) NOT NULL DEFAULT 2.0,
    image           VARCHAR(128) NOT NULL DEFAULT 'sunshine-sandbox-full:latest',
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    INDEX idx_ws_tenant_user (tenant_id, user_id, status)
);

-- V19__conversation_kind_workspace.sql
ALTER TABLE chat_conversation
  ADD COLUMN kind          VARCHAR(16)  NOT NULL DEFAULT 'chat' COMMENT 'chat / task',
  ADD COLUMN workspace_id  VARCHAR(64)  NULL COMMENT 'kind=task 时必填',
  ADD COLUMN checkout_path VARCHAR(256) NULL COMMENT '用户选定的 checkout';

-- V20__workspace_project_guide.sql
-- 项目级规范（类 CLAUDE.md）：用户手动维护，随工作区共享，注入 task 场景上下文
CREATE TABLE workspace_project_guide (
    workspace_id VARCHAR(64)  NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(64)  NOT NULL DEFAULT 'default',
    user_id      VARCHAR(64)  NOT NULL,
    content      MEDIUMTEXT   NOT NULL,
    updated_by   VARCHAR(64)  NULL,
    created_at   DATETIME(3)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL
);
