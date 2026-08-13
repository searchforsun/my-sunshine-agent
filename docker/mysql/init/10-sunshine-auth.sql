-- sunshine-auth（auth-center :8100 · 库 sunshine_auth · 全量 v1）
USE sunshine_auth;
CREATE TABLE sys_user (
    id            VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT 'UUID',
    username      VARCHAR(32)  NOT NULL COMMENT '登录名',
    password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt',
    nickname      VARCHAR(64)  NULL     COMMENT '展示名',
    tenant_id     VARCHAR(32)  NOT NULL DEFAULT 'default' COMMENT '租户标识',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=禁用',
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    default_write_hitl_mode VARCHAR(16) NOT NULL DEFAULT 'never' COMMENT 'never|always|smart 沙箱写 HITL 用户默认',
    sidebar_sections_layout VARCHAR(16) NOT NULL DEFAULT 'vertical' COMMENT 'vertical|horizontal 侧栏平台/对话/任务排布',
    default_kb_id VARCHAR(64) NULL COMMENT '对话默认知识库 ID（账号级；会话可覆盖）',
    personal_rules TEXT NULL COMMENT '用户个人规则（soul），注入系统提示',
    github_url     VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'GitHub 基础地址',
    github_token   VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'GitHub PAT',
    gitlab_url     VARCHAR(255) NOT NULL DEFAULT '' COMMENT '内网 GitLab 基础地址',
    gitlab_token   VARCHAR(255) NOT NULL DEFAULT '' COMMENT '内网 GitLab PAT',
    UNIQUE KEY uk_username (username),
    INDEX idx_sys_user_tenant (tenant_id)
);
-- 演示用户（biz CRUD / corpus50 联调；密码与现网测试账号同一口令）
INSERT IGNORE INTO sys_user (id, username, password_hash, nickname, status, created_at, updated_at, tenant_id, default_write_hitl_mode) VALUES
('a1111111-1111-4111-a111-111111111111', 'alice', '$2a$10$56JywJyd.ICYkiKmDc7jI.5RFrwrYDzETgcY6QsITMRjABIhFKW62', '爱丽丝', 1, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000', 'default', 'never'),
('b2222222-2222-4222-b222-222222222222', 'bob',   '$2a$10$56JywJyd.ICYkiKmDc7jI.5RFrwrYDzETgcY6QsITMRjABIhFKW62', '鲍勃',   1, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000', 'default', 'never'),
('c3333333-3333-4333-c333-333333333333', 'carol','$2a$10$56JywJyd.ICYkiKmDc7jI.5RFrwrYDzETgcY6QsITMRjABIhFKW62', '卡罗尔', 1, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000', 'default', 'never');
