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
