CREATE TABLE IF NOT EXISTS hr_leave_balance (
    tenant_id     VARCHAR(32)    NOT NULL DEFAULT 'default',
    user_id       VARCHAR(64)    NOT NULL,
    `year`        INT            NOT NULL,
    annual        DECIMAL(8,1)   NOT NULL DEFAULT 0,
    qingsong      DECIMAL(8,1)   NOT NULL DEFAULT 0,
    compensatory  DECIMAL(8,1)   NOT NULL DEFAULT 0,
    created_at    TIMESTAMP(3)   NOT NULL,
    updated_at    TIMESTAMP(3)   NOT NULL,
    PRIMARY KEY (tenant_id, user_id, `year`)
);

CREATE TABLE IF NOT EXISTS hr_leave_request (
    id           VARCHAR(64)  NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(32)  NOT NULL DEFAULT 'default',
    user_id      VARCHAR(64)  NOT NULL,
    leave_type   VARCHAR(32)  NOT NULL,
    start_date   DATE         NOT NULL,
    end_date     DATE         NOT NULL,
    reason       VARCHAR(512) NULL,
    status       VARCHAR(32)  NOT NULL,
    created_at   TIMESTAMP(3) NOT NULL,
    updated_at   TIMESTAMP(3) NOT NULL
);

CREATE TABLE IF NOT EXISTS hr_attendance_month (
    tenant_id             VARCHAR(32)   NOT NULL DEFAULT 'default',
    user_id               VARCHAR(64)   NOT NULL,
    year_month            VARCHAR(7)    NOT NULL,
    late_count            INT           NOT NULL DEFAULT 0,
    overtime_hours        DECIMAL(8,1)  NOT NULL DEFAULT 0,
    frost_ledger_summary  VARCHAR(512)  NULL,
    created_at            TIMESTAMP(3)  NOT NULL,
    updated_at            TIMESTAMP(3)  NOT NULL,
    PRIMARY KEY (tenant_id, user_id, year_month)
);
