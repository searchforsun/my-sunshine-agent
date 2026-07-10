-- sunshine-tool-manager（tool-manager :8210）
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
    output_summary_kind VARCHAR(32) NOT NULL DEFAULT 'truncate',
    side_effect         VARCHAR(16) NOT NULL DEFAULT 'read',
    require_confirmation TINYINT(1) NOT NULL DEFAULT 0,
    confirmation_edited TINYINT(1) NOT NULL DEFAULT 0,
    tenant_id           VARCHAR(32) NOT NULL DEFAULT 'default',
    enabled             TINYINT(1) NOT NULL DEFAULT 0,
    metadata_edited     TINYINT(1) NOT NULL DEFAULT 0,
    id_valid            TINYINT(1) NOT NULL DEFAULT 1,
    id_error            VARCHAR(512),
    discovered_at       TIMESTAMP NULL,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
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
('sunshine-finance', 'sunshine-finance', '财务 Demo 应用', 'default', 'offline'),
('sunshine-oa', 'sunshine-oa', 'OA Demo 应用', 'default', 'offline');

INSERT INTO tool_set (id, set_type, tenant_id, display_name) VALUES
('global-react-default', 'global_react_default', NULL, '平台 ReAct 默认工具集'),
('global-plan-workflow-critical', 'global_plan_workflow_critical', NULL, '平台 Plan/Workflow 关键工具集');

INSERT INTO tool_set_member (set_id, tool_id, sort_order) VALUES
('global-react-default', 'sdk__sunshine-finance__list_finance_messages', 0),
('global-react-default', 'sdk__sunshine-finance__get_finance_message_detail', 1),
('global-react-default', 'sdk__sunshine-finance__summarize_finance_by_status', 2),
('global-react-default', 'sdk__sunshine-oa__list_oa_tasks', 3),
('global-react-default', 'sdk__sunshine-oa__approve_oa_task', 4),
('global-plan-workflow-critical', 'sdk__sunshine-finance__list_finance_messages', 0),
('global-plan-workflow-critical', 'sdk__sunshine-finance__get_finance_message_detail', 1);

INSERT INTO execution_mode_policy (id, mode_key, tenant_id, policy_json) VALUES
('global-plan-workflow-policy', 'plan_workflow', NULL, JSON_OBJECT(
  'criticalOnFailure', 'fail_fast',
  'defaults', JSON_OBJECT(
    'maxAttempts', 2,
    'backoffMs', 500,
    'backoffMultiplier', 2.0,
    'onFailure', 'continue',
    'retryOnErrorClass', JSON_ARRAY('TIMEOUT', 'SERVICE_UNAVAILABLE', 'CIRCUIT_OPEN')
  ),
  'byType', JSON_OBJECT(
    'rag', JSON_OBJECT('maxAttempts', 1),
    'tool', JSON_OBJECT('maxAttempts', 2),
    'agent', JSON_OBJECT('maxAttempts', 1),
    'answer', JSON_OBJECT('maxAttempts', 2, 'onFailure', 'fail_fast')
  )
));
