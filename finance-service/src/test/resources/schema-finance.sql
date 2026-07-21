CREATE TABLE IF NOT EXISTS fin_expense (
    id           VARCHAR(64)   NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(32)   NOT NULL DEFAULT 'default',
    user_id      VARCHAR(64)   NOT NULL,
    category     VARCHAR(64)   NOT NULL,
    amount       DECIMAL(12,2) NOT NULL,
    status       VARCHAR(32)   NOT NULL,
    occurred_on  DATE          NOT NULL,
    remark       VARCHAR(512)  NULL,
    created_at   TIMESTAMP(3)  NOT NULL,
    updated_at   TIMESTAMP(3)  NOT NULL
);

CREATE TABLE IF NOT EXISTS fin_inbox (
    id           VARCHAR(64)   NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(32)   NOT NULL DEFAULT 'default',
    user_id      VARCHAR(64)   NOT NULL,
    title        VARCHAR(256)  NOT NULL,
    status       VARCHAR(32)   NOT NULL,
    amount       DECIMAL(12,2) NULL,
    created_at   TIMESTAMP(3)  NOT NULL,
    updated_at   TIMESTAMP(3)  NOT NULL
);
