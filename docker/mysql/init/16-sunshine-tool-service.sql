-- sunshine-tool-manager（tool-manager :8210 · 库 sunshine_tool · 全量 v1）
USE sunshine_tool;

CREATE TABLE sdk_application (
    id              VARCHAR(64) PRIMARY KEY,
    nacos_service   VARCHAR(128) NOT NULL,
    display_name    VARCHAR(128),
    catalog_path    VARCHAR(256) NOT NULL DEFAULT '/sunshine/tools/catalog',
    invoke_path     VARCHAR(256) NOT NULL DEFAULT '/sunshine/tools/invoke',
    tenant_id       VARCHAR(32) NOT NULL DEFAULT 'default',
    status          VARCHAR(16) NOT NULL DEFAULT 'offline',
    last_seen_at    TIMESTAMP NULL,
    schema_version  INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE mcp_server (
    id              VARCHAR(64) PRIMARY KEY,
    display_name    VARCHAR(128),
    transport       VARCHAR(16) NOT NULL,
    command         VARCHAR(512),
    args_json       JSON,
    endpoint        VARCHAR(512),
    env_json        JSON,
    tenant_id       VARCHAR(32) NOT NULL DEFAULT 'default',
    enabled         TINYINT(1) NOT NULL DEFAULT 0,
    last_probe_at   TIMESTAMP NULL,
    probe_status    VARCHAR(16),
    probe_error     VARCHAR(512),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE tool_definition (
    id                  VARCHAR(128) PRIMARY KEY,
    source              VARCHAR(16) NOT NULL,
    source_ref          VARCHAR(64) NOT NULL,
    external_name       VARCHAR(128) NOT NULL,
    display_name        VARCHAR(128) NOT NULL,
    description         TEXT,
    schema_json         JSON NOT NULL,
    schema_hash         VARCHAR(64),
    kind                VARCHAR(16) NOT NULL,
    timeline_phase      VARCHAR(16) NOT NULL DEFAULT 'tool',
    timeline_summary_template VARCHAR(512) NOT NULL DEFAULT '',
    timeline_summary_extract TEXT,
    side_effect         VARCHAR(16) NOT NULL DEFAULT 'read',
    require_confirmation TINYINT(1) NOT NULL DEFAULT 0,
    confirmation_edited TINYINT(1) NOT NULL DEFAULT 0,
    tenant_id           VARCHAR(32) NOT NULL DEFAULT 'default',
    enabled             TINYINT(1) NOT NULL DEFAULT 0,
    metadata_edited     TINYINT(1) NOT NULL DEFAULT 0,
    discovered_at       TIMESTAMP NULL,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    id_valid            TINYINT(1) NOT NULL DEFAULT 1,
    id_error            VARCHAR(512),
    UNIQUE KEY uk_source_tool (source, source_ref, external_name)
);

CREATE TABLE tool_set (
    id              VARCHAR(64) PRIMARY KEY,
    set_type        VARCHAR(32) NOT NULL,
    tenant_id       VARCHAR(32),
    display_name    VARCHAR(128),
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_set_type_tenant (set_type, tenant_id)
);

CREATE TABLE tool_set_member (
    set_id          VARCHAR(64) NOT NULL,
    tool_id         VARCHAR(128) NOT NULL,
    sort_order      INT NOT NULL DEFAULT 0,
    PRIMARY KEY (set_id, tool_id)
);

CREATE TABLE execution_mode_policy (
    id              VARCHAR(64) PRIMARY KEY,
    mode_key        VARCHAR(32) NOT NULL,
    tenant_id       VARCHAR(32),
    policy_json     JSON NOT NULL,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_mode_tenant (mode_key, tenant_id)
);

INSERT INTO sdk_application (id, nacos_service, display_name, tenant_id, status) VALUES
('sunshine-biz', 'sunshine-biz-simulator', '业务模拟应用', 'default', 'offline');

-- 默认工具集（会话 kind：chat | task）
INSERT INTO tool_set (id, set_type, tenant_id, display_name) VALUES
('global-chat-default', 'global_chat_default', NULL, '平台 Chat 工具集'),
('global-task-default', 'global_task_default', NULL, '平台 Task 工具集');

-- 工具集成员（chat/task 各 12 条，与线上 active 一致）
INSERT INTO tool_set_member (set_id, tool_id, sort_order) VALUES
('global-chat-default', 'sdk__sunshine-biz__list_my_expenses', 0),
('global-chat-default', 'sdk__sunshine-biz__approve_oa_task', 1),
('global-chat-default', 'sdk__sunshine-biz__get_attendance_month', 2),
('global-chat-default', 'sdk__sunshine-biz__get_expense_detail', 3),
('global-chat-default', 'sdk__sunshine-biz__get_finance_inbox_item', 4),
('global-chat-default', 'sdk__sunshine-biz__get_leave_balance', 5),
('global-chat-default', 'sdk__sunshine-biz__list_leave_requests', 6),
('global-chat-default', 'sdk__sunshine-biz__list_my_finance_inbox', 7),
('global-chat-default', 'sdk__sunshine-biz__list_oa_tasks', 8),
('global-chat-default', 'sdk__sunshine-biz__submit_expense', 9),
('global-chat-default', 'sdk__sunshine-biz__submit_leave_request', 10),
('global-chat-default', 'sdk__sunshine-biz__summarize_my_expenses', 11),
('global-task-default', 'sdk__sunshine-biz__approve_oa_task', 0),
('global-task-default', 'sdk__sunshine-biz__get_attendance_month', 1),
('global-task-default', 'sdk__sunshine-biz__get_expense_detail', 2),
('global-task-default', 'sdk__sunshine-biz__get_finance_inbox_item', 3),
('global-task-default', 'sdk__sunshine-biz__get_leave_balance', 4),
('global-task-default', 'sdk__sunshine-biz__list_leave_requests', 5),
('global-task-default', 'sdk__sunshine-biz__list_my_expenses', 6),
('global-task-default', 'sdk__sunshine-biz__list_my_finance_inbox', 7),
('global-task-default', 'sdk__sunshine-biz__list_oa_tasks', 8),
('global-task-default', 'sdk__sunshine-biz__submit_expense', 9),
('global-task-default', 'sdk__sunshine-biz__submit_leave_request', 10),
('global-task-default', 'sdk__sunshine-biz__summarize_my_expenses', 11);
