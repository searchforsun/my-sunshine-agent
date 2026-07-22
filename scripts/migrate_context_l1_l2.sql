-- 一次性：旧 MTM/LTM → L1/L2（开发期不兼容迁移，禁止 Flyway）
-- 用法: mysql -h ecs4c16g -uroot -proot123 < scripts/migrate_context_l1_l2.sql
-- 或: python -c "from sunshine_lib import run_mysql; ..." 读本文件执行

USE sunshine_chat;

DROP TABLE IF EXISTS conversation_memory_mtm, user_memory_profile;

CREATE TABLE IF NOT EXISTS conversation_context_l1 (
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

-- 已有库缺列时手工执行（CREATE IF NOT EXISTS 不会补列）:
-- ALTER TABLE conversation_context_l1
--   ADD COLUMN far_folded_msg_ids MEDIUMTEXT NULL
--   COMMENT 'JSON array of msgIds already folded into far_summary' AFTER far_summary;

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
    -- 无 UNIQUE(kind,key)：同 key 可多条 superseded 审计；应用保证至多一条 active
    INDEX idx_ctx_user_kind_key_status (user_id, tenant_id, kind, state_key, status),
    INDEX idx_ctx_user_status (user_id, tenant_id, status),
    INDEX idx_ctx_expires (expires_at)
);

-- 已有库若仍为旧 UNIQUE(uk_ctx_user_kind_key)，手工迁移：
-- ALTER TABLE user_context_state DROP INDEX uk_ctx_user_kind_key;
-- ALTER TABLE user_context_state
--   ADD INDEX idx_ctx_user_kind_key_status (user_id, tenant_id, kind, state_key, status);
