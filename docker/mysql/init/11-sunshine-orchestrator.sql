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
    execution_preference VARCHAR(32) NULL COMMENT 'fast|pro|workflow',
    kb_id       VARCHAR(64)  NULL COMMENT '会话绑定的知识库 id',
    kind        VARCHAR(16)  NOT NULL DEFAULT 'chat' COMMENT 'chat / task',
    workspace_id  VARCHAR(64)  NULL COMMENT 'kind=task 时必填',
    checkout_path VARCHAR(256) NULL COMMENT '用户选定的 checkout',
    model_name  VARCHAR(128) NULL COMMENT '会话绑定模型（注册表 model_name；空则走 chat/default scene）',
    INDEX idx_user_tenant_updated (user_id, tenant_id, updated_at)
);

-- 消息表（含 reasoning / steps / content_blocks / workflow_id / execution_plan_id / react_pause_checkpoint / execution_preference）
CREATE TABLE chat_message (
    id               VARCHAR(64)  NOT NULL PRIMARY KEY,
    conversation_id  VARCHAR(64)  NOT NULL,
    seq              INT          NOT NULL,
    role             VARCHAR(16)  NOT NULL,
    content          MEDIUMTEXT   NOT NULL,
    reasoning        MEDIUMTEXT   NULL,
    steps            MEDIUMTEXT   NULL,
    content_blocks   MEDIUMTEXT   NULL COMMENT 'ReAct 正文分段 JSON',
    usage_json       MEDIUMTEXT   NULL COMMENT '消息级 LLM usage + 上下文分组快照 JSON',
    status           VARCHAR(16)  NOT NULL DEFAULT 'completed',
    intent           VARCHAR(32)  NULL,
    workflow_id      VARCHAR(64)  NULL,
    execution_plan_id VARCHAR(36) NULL,
    routing_skill_ids MEDIUMTEXT  NULL COMMENT '本轮已触发 skill 集（逗号分隔；skill-sticky S-0）',
    routing_agent_ids MEDIUMTEXT  NULL COMMENT '本轮可调度 agent 集（逗号分隔；skill-sticky S-0）',
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
    far_folded_msg_ids   MEDIUMTEXT   NULL COMMENT 'JSON array of msgIds 已退役（压缩点之前；Near 起点=该边界之后）',
    far_summarized_msg_ids MEDIUMTEXT NULL COMMENT 'JSON array of msgIds 已实际折叠进 far_summary（far_folded 子集；NULL=存量行，视为与 far_folded 一致）',
    near_n               INT          NOT NULL DEFAULT 8,
    mid_n                INT          NOT NULL DEFAULT 8,
    updated_at           TIMESTAMP(3) NOT NULL,
    INDEX idx_l1_user (user_id, tenant_id)
);

-- L2 用户状态
CREATE TABLE user_context_state (
    id              VARCHAR(32)  NOT NULL PRIMARY KEY,
    scope           VARCHAR(16)  NOT NULL DEFAULT 'user' COMMENT 'user|workspace',
    user_id         VARCHAR(64)  NOT NULL,
    workspace_id    VARCHAR(64)  NULL COMMENT 'scope=workspace 时的工作区 id（scope=user 为 NULL）',
    tenant_id       VARCHAR(32)  NOT NULL DEFAULT 'default',
    kind            VARCHAR(32)  NOT NULL,
    state_key       VARCHAR(128) NOT NULL,
    state_value     TEXT         NOT NULL,
    background      VARCHAR(256) NULL COMMENT '成立场景背景（v20）',
    biz_scene_scope VARCHAR(64)  NULL DEFAULT '*' COMMENT '场景偏好作用域（业务权威层 §4.3）：*=全局 | 具体 biz_scene',
    confirm_status  VARCHAR(16)  NULL DEFAULT 'confirmed' COMMENT '偏好确认态（业务权威层 §4.3）：confirmed 默认可装载 | inferred 默认不装载',
    confidence      DOUBLE       NOT NULL DEFAULT 0,
    status          VARCHAR(16)  NOT NULL DEFAULT 'active',
    expires_at      TIMESTAMP(3) NULL,
    source_msg_id   VARCHAR(64)  NULL,
    created_at      TIMESTAMP(3) NOT NULL,
    updated_at      TIMESTAMP(3) NOT NULL,
    -- 无 UNIQUE(kind,key)：同 key 可多条 superseded 审计；应用保证至多一条 active
    INDEX idx_ctx_user_kind_key_status (user_id, tenant_id, kind, state_key, status),
    INDEX idx_ctx_user_status (user_id, tenant_id, status),
    INDEX idx_ctx_ws_kind_key_status (workspace_id, tenant_id, kind, state_key, status),
    INDEX idx_ctx_expires (expires_at)
);

-- 业务任务板权威态（业务权威层 §4.1）：跨会话流程状态；与 agent 执行态边界隔离
CREATE TABLE business_task (
    task_id                      VARCHAR(32)  NOT NULL PRIMARY KEY,
    tenant_id                    VARCHAR(32)  NOT NULL DEFAULT 'default',
    user_id                      VARCHAR(64)  NOT NULL,
    biz_scene                    VARCHAR(64)  NOT NULL,
    status                       VARCHAR(24)  NOT NULL DEFAULT 'pending'
        COMMENT 'pending|running|awaiting_confirm|done|archived|failed',
    title                        VARCHAR(128) NOT NULL,
    steps_json                   TEXT         NULL COMMENT '当前步骤骨架（结构化，非散文全文）',
    pending_confirmations_json   TEXT         NULL COMMENT '待确认项',
    retry_count                  INT          NOT NULL DEFAULT 0,
    deadline                     TIMESTAMP(3) NULL,
    risk_level                   VARCHAR(16)  NULL DEFAULT 'low' COMMENT 'low|medium|high',
    external_ticket_ref          VARCHAR(128) NULL COMMENT '外系统工单/审批单号指针（不含原文）',
    evidence_refs_json           TEXT         NULL COMMENT '证据指针列表（OSS key / messageId / ticket）',
    conversation_id              VARCHAR(64)  NULL COMMENT '最近关联会话',
    created_at                   TIMESTAMP(3) NOT NULL,
    updated_at                   TIMESTAMP(3) NOT NULL,
    INDEX idx_biz_task_user_status (tenant_id, user_id, status, updated_at),
    INDEX idx_biz_task_scene (tenant_id, user_id, biz_scene, status, updated_at),
    INDEX idx_biz_task_ticket (tenant_id, external_ticket_ref)
);

-- 执行计划快照（静态 Workflow 落库 + 暂停续跑检查点；旧动态 Plan-Workflow 已下线）
CREATE TABLE execution_plan (
    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    conversation_id  VARCHAR(64)  NOT NULL,
    message_id       VARCHAR(64)  NOT NULL,
    user_id          VARCHAR(64)  NOT NULL,
    tenant_id        VARCHAR(64)  NOT NULL DEFAULT 'default',
    status           VARCHAR(24)  NOT NULL,
    plan_json        MEDIUMTEXT   NOT NULL,
    validated_json   MEDIUMTEXT   NULL,
    execution_trace  MEDIUMTEXT   NULL,
    pause_checkpoint MEDIUMTEXT   NULL COMMENT '暂停续跑 JSON：resumeNodeId + wfCtx',
    trace_id         VARCHAR(64)  NULL,
    created_at       DATETIME(3)  NOT NULL,
    validated_at     DATETIME(3)  NULL,
    started_at       DATETIME(3)  NULL,
    completed_at     DATETIME(3)  NULL,
    INDEX idx_ep_conv (conversation_id),
    INDEX idx_ep_msg (message_id)
);

-- 任务板终态快照（原生 todo_write 收口落库）
CREATE TABLE task_board (
    id              VARCHAR(64)  NOT NULL PRIMARY KEY,
    message_id      VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'default',
    user_id         VARCHAR(64)  NOT NULL,
    revision        INT          NOT NULL,
    items_json      JSON         NOT NULL,
    created_at      DATETIME(3)  NOT NULL,
    updated_at      DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_task_board_msg (message_id),
    INDEX idx_task_board_conv (conversation_id, updated_at)
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

-- LLM 调用用量记录（phase5 5.2 阶段一：token 全量落库闭环）
-- 消费端 orchestrator（MQ topic=llm-usage）；call_site/run_id/round_id 为 5.3 链路透传预留
CREATE TABLE llm_usage_record (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL DEFAULT 'default',
    user_id           VARCHAR(64)  NULL COMMENT '链路透传后填充（阶段一预留）',
    model             VARCHAR(128) NOT NULL,
    call_site         VARCHAR(32)  NULL COMMENT 'chat|plan|worker|tool-call|rewrite|summarize|subagent（5.3）',
    run_id            VARCHAR(64)  NULL,
    round_id          VARCHAR(64)  NULL,
    stream            TINYINT(1)   NOT NULL DEFAULT 0,
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    total_tokens      INT          NOT NULL DEFAULT 0,
    estimated         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '流式缺失 usage 时按 messages 估算',
    request_at        DATETIME(3)  NOT NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_usage_request_at (request_at),
    INDEX idx_usage_model (model),
    INDEX idx_usage_tenant (tenant_id, request_at)
);

-- LLM 用量日聚合（phase5 5.2.3）
-- 聚合任务 UsageDailyAggregationJob 每小时级 upsert（按 stat_date/tenant/model/call_site 重建），
-- est_cost 按 Nacos llm-usage.price 模型单价估算（元）
CREATE TABLE llm_usage_daily (
    id                BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    stat_date         DATE          NOT NULL,
    tenant_id         VARCHAR(64)   NOT NULL DEFAULT 'default',
    model             VARCHAR(128)  NOT NULL,
    call_site         VARCHAR(32)   NULL COMMENT '5.3 链路透传后按调用点聚合（阶段一为 NULL）',
    calls             INT           NOT NULL DEFAULT 0,
    prompt_tokens     BIGINT        NOT NULL DEFAULT 0,
    completion_tokens BIGINT        NOT NULL DEFAULT 0,
    total_tokens      BIGINT        NOT NULL DEFAULT 0,
    est_cost          DECIMAL(14,6) NOT NULL DEFAULT 0 COMMENT '估算成本（元）',
    updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_usage_daily (stat_date, tenant_id, model, call_site)
);

-- 租户月用量配额（phase5 5.2.4）
-- 校验端 orchestrator /api/usage/quota/check（聚合 llm_usage_record 当月用量）；
-- llm-gateway 请求前切面调用该端点，超限 429（code=quota_exceeded / model_not_allowed）
CREATE TABLE tenant_quota (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    month_token_limit BIGINT       NOT NULL DEFAULT 0 COMMENT '月 token 上限（0=不限额）',
    model_whitelist   VARCHAR(512) NULL COMMENT '模型白名单 JSON 数组（NULL=不限制）',
    enabled           TINYINT(1)   NOT NULL DEFAULT 1,
    remark            VARCHAR(255) NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_tenant_quota (tenant_id)
);
