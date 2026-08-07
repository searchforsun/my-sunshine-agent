-- sunshine-orchestrator（orchestrator :8200 · 库 sunshine_chat · 全量 v1）
USE sunshine_chat;

-- 会话表（含 execution_preference / kb_id / kind / workspace_id / checkout_path）
CREATE TABLE chat_conversation (
    id          VARCHAR(64)  NOT NULL PRIMARY KEY,
    user_id     VARCHAR(64)  NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'default',
    title       VARCHAR(128) NOT NULL DEFAULT '新对话',
    created_at  DATETIME(3)  NOT NULL,
    updated_at  DATETIME(3)  NOT NULL,
    execution_preference VARCHAR(32) NULL COMMENT 'auto|simple-llm|react|workflow|plan-workflow',
    kb_id       VARCHAR(64)  NULL COMMENT '会话绑定的知识库 id',
    kind        VARCHAR(16)  NOT NULL DEFAULT 'chat' COMMENT 'chat / task',
    workspace_id  VARCHAR(64)  NULL COMMENT 'kind=task 时必填',
    checkout_path VARCHAR(256) NULL COMMENT '用户选定的 checkout',
    INDEX idx_user_tenant_updated (user_id, tenant_id, updated_at)
);

-- 消息表（含 reasoning / steps / content_blocks / execution_mode / workflow_id / execution_plan_id / react_pause_checkpoint / execution_preference）
CREATE TABLE chat_message (
    id               VARCHAR(64)  NOT NULL PRIMARY KEY,
    conversation_id  VARCHAR(64)  NOT NULL,
    seq              INT          NOT NULL,
    role             VARCHAR(16)  NOT NULL,
    content          MEDIUMTEXT   NOT NULL,
    reasoning        MEDIUMTEXT   NULL,
    steps            MEDIUMTEXT   NULL,
    content_blocks   MEDIUMTEXT   NULL COMMENT 'ReAct 正文分段 JSON',
    status           VARCHAR(16)  NOT NULL DEFAULT 'completed',
    intent           VARCHAR(32)  NULL,
    execution_mode   VARCHAR(16)  NULL,
    workflow_id      VARCHAR(64)  NULL,
    execution_plan_id VARCHAR(36) NULL,
    resume_count     INT          NOT NULL DEFAULT 0,
    react_pause_checkpoint MEDIUMTEXT NULL COMMENT 'ReAct 暂停续跑 JSON',
    created_at       DATETIME(3)  NOT NULL,
    updated_at       DATETIME(3)  NOT NULL,
    execution_preference VARCHAR(32) NULL COMMENT 'user 消息发送时 executionPreference',
    UNIQUE KEY uk_conv_seq (conversation_id, seq),
    INDEX idx_conv_created (conversation_id, created_at),
    INDEX idx_msg_execution_plan (execution_plan_id),
    CONSTRAINT fk_msg_conv FOREIGN KEY (conversation_id) REFERENCES chat_conversation(id) ON DELETE CASCADE
);

-- 审计日志
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

-- L1 派生摘要（废止 conversation_memory_mtm / user_memory_profile）
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

-- L2 用户状态
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

-- 动态 Plan 执行记录（含 planner_attempts / replan_count / approval_rounds / pause_checkpoint）
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
    planner_attempts MEDIUMTEXT   NULL,
    approval_rounds  MEDIUMTEXT   NULL COMMENT 'Plan 用户确认轮次 JSON',
    replan_count     INT          NOT NULL DEFAULT 0,
    validated_json   MEDIUMTEXT   NULL,
    execution_trace  MEDIUMTEXT   NULL,
    pause_checkpoint MEDIUMTEXT   NULL COMMENT '暂停续跑 JSON：resumeNodeId + wfCtx',
    trace_id         VARCHAR(64)  NULL,
    reject_reason    VARCHAR(512) NULL,
    created_at       DATETIME(3)  NOT NULL,
    validated_at     DATETIME(3)  NULL,
    started_at       DATETIME(3)  NULL,
    completed_at     DATETIME(3)  NULL,
    INDEX idx_ep_conv (conversation_id),
    INDEX idx_ep_msg (message_id)
);

-- ReAct TaskBoard
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

-- 沙箱工作区（task 场景）
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
