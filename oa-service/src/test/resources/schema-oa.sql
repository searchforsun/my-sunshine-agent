CREATE TABLE IF NOT EXISTS oa_task (
    id               VARCHAR(64)  NOT NULL PRIMARY KEY,
    tenant_id        VARCHAR(32)  NOT NULL DEFAULT 'default',
    assignee_user_id VARCHAR(64)  NOT NULL,
    title            VARCHAR(256) NOT NULL,
    category         VARCHAR(64)  NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    created_at       TIMESTAMP(3) NOT NULL,
    updated_at       TIMESTAMP(3) NOT NULL
);
