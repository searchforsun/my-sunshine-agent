-- 一次性：旧 MTM/LTM → L1/L2（开发期不兼容迁移，禁止 Flyway）
-- 用法: mysql -h ecs4c16g -uroot -proot123 < scripts/migrate_context_l1_l2.sql
-- 或: python -c "from sunshine_lib import run_mysql; ..." 读本文件执行

USE sunshine_chat;

DROP TABLE IF EXISTS conversation_memory_mtm, user_memory_profile;

CREATE TABLE IF NOT EXISTS conversation_context_l1 (
    conv_id       VARCHAR(32)  NOT NULL PRIMARY KEY,
    user_id       VARCHAR(64)  NOT NULL,
    tenant_id     VARCHAR(32)  NOT NULL DEFAULT 'default',
    mid_answers   MEDIUMTEXT   NULL COMMENT 'JSON map msgId -> answer summary',
    far_summary   MEDIUMTEXT   NULL,
    near_n        INT          NOT NULL DEFAULT 8,
    mid_n         INT          NOT NULL DEFAULT 8,
    updated_at    TIMESTAMP(3) NOT NULL,
    INDEX idx_l1_user (user_id, tenant_id)
);

CREATE TABLE IF NOT EXISTS user_context_state (
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
    UNIQUE KEY uk_ctx_user_kind_key (user_id, tenant_id, kind, state_key),
    INDEX idx_ctx_user_status (user_id, tenant_id, status),
    INDEX idx_ctx_expires (expires_at)
);
