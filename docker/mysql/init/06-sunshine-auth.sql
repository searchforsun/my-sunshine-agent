-- Sunshine 业务库：sunshine_auth
USE sunshine_auth;

-- V1__sys_user.sql
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

-- V2__tenant_id.sql
ALTER TABLE sys_user
    ADD COLUMN tenant_id VARCHAR(32) NOT NULL DEFAULT 'default' COMMENT '租户标识' AFTER nickname;

CREATE INDEX idx_sys_user_tenant ON sys_user (tenant_id);

-- Flyway 基线（与 classpath db/migration 校验一致，避免服务启动重复建表）
USE sunshine_auth;
CREATE TABLE IF NOT EXISTS flyway_schema_history (
    installed_rank INT NOT NULL,
    version VARCHAR(50),
    description VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL,
    script VARCHAR(1000) NOT NULL,
    checksum INT,
    installed_by VARCHAR(100) NOT NULL,
    installed_on TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    execution_time INT NOT NULL,
    success TINYINT(1) NOT NULL,
    PRIMARY KEY (installed_rank),
    INDEX flyway_schema_history_s_idx (success)
);
DELETE FROM flyway_schema_history;
INSERT INTO flyway_schema_history VALUES (1, '1', 'sys user', 'SQL', 'V1__sys_user.sql', -466363544, 'docker-init', NOW(), 0, 1);
INSERT INTO flyway_schema_history VALUES (2, '2', 'tenant id', 'SQL', 'V2__tenant_id.sql', -1614021549, 'docker-init', NOW(), 0, 1);
