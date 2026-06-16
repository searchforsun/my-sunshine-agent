CREATE TABLE sys_user (
    id            VARCHAR(64)  NOT NULL PRIMARY KEY COMMENT 'UUID',
    username      VARCHAR(32)  NOT NULL COMMENT '登录名',
    password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt',
    nickname      VARCHAR(64)  NULL     COMMENT '展示名',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=禁用',
    created_at    DATETIME(3)  NOT NULL,
    updated_at    DATETIME(3)  NOT NULL,
    UNIQUE KEY uk_username (username)
);
