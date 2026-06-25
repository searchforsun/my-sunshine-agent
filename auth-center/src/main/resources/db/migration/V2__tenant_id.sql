ALTER TABLE sys_user
    ADD COLUMN tenant_id VARCHAR(32) NOT NULL DEFAULT 'default' COMMENT '租户标识' AFTER nickname;

CREATE INDEX idx_sys_user_tenant ON sys_user (tenant_id);
