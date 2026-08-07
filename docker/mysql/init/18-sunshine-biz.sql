-- sunshine-biz（finance / oa / hr 共享业务库 · 库 sunshine_biz · 全量 v1）
USE sunshine_biz;
-- 报销单
CREATE TABLE IF NOT EXISTS fin_expense (
    id           VARCHAR(64)   NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(32)   NOT NULL DEFAULT 'default',
    user_id      VARCHAR(64)   NOT NULL,
    category     VARCHAR(64)   NOT NULL,
    amount       DECIMAL(12,2) NOT NULL,
    status       VARCHAR(32)   NOT NULL,
    occurred_on  DATE          NOT NULL,
    remark       VARCHAR(512)  NULL,
    created_at   DATETIME(3)   NOT NULL,
    updated_at   DATETIME(3)   NOT NULL,
    INDEX idx_fin_expense_user (tenant_id, user_id),
    INDEX idx_fin_expense_status (tenant_id, user_id, status)
);
-- 财务待办
CREATE TABLE IF NOT EXISTS fin_inbox (
    id           VARCHAR(64)   NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(32)   NOT NULL DEFAULT 'default',
    user_id      VARCHAR(64)   NOT NULL,
    title        VARCHAR(256)  NOT NULL,
    status       VARCHAR(32)   NOT NULL,
    amount       DECIMAL(12,2) NULL,
    created_at   DATETIME(3)   NOT NULL,
    updated_at   DATETIME(3)   NOT NULL,
    INDEX idx_fin_inbox_user (tenant_id, user_id),
    INDEX idx_fin_inbox_status (tenant_id, user_id, status)
);
-- OA 待办
CREATE TABLE IF NOT EXISTS oa_task (
    id               VARCHAR(64)  NOT NULL PRIMARY KEY,
    tenant_id        VARCHAR(32)  NOT NULL DEFAULT 'default',
    assignee_user_id VARCHAR(64)  NOT NULL,
    title            VARCHAR(256) NOT NULL,
    category         VARCHAR(64)  NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    created_at       DATETIME(3)  NOT NULL,
    updated_at       DATETIME(3)  NOT NULL,
    INDEX idx_oa_task_assignee (tenant_id, assignee_user_id),
    INDEX idx_oa_task_status (tenant_id, assignee_user_id, status)
);
-- 假期余额
CREATE TABLE IF NOT EXISTS hr_leave_balance (
    tenant_id     VARCHAR(32)    NOT NULL DEFAULT 'default',
    user_id       VARCHAR(64)    NOT NULL,
    `year`        INT            NOT NULL,
    annual        DECIMAL(8,1)   NOT NULL DEFAULT 0,
    qingsong      DECIMAL(8,1)   NOT NULL DEFAULT 0,
    compensatory  DECIMAL(8,1)   NOT NULL DEFAULT 0,
    created_at    DATETIME(3)    NOT NULL,
    updated_at    DATETIME(3)    NOT NULL,
    PRIMARY KEY (tenant_id, user_id, `year`)
);
-- 请假单
CREATE TABLE IF NOT EXISTS hr_leave_request (
    id           VARCHAR(64)  NOT NULL PRIMARY KEY,
    tenant_id    VARCHAR(32)  NOT NULL DEFAULT 'default',
    user_id      VARCHAR(64)  NOT NULL,
    leave_type   VARCHAR(32)  NOT NULL,
    start_date   DATE         NOT NULL,
    end_date     DATE         NOT NULL,
    reason       VARCHAR(512) NULL,
    status       VARCHAR(32)  NOT NULL,
    created_at   DATETIME(3)  NOT NULL,
    updated_at   DATETIME(3)  NOT NULL,
    INDEX idx_hr_leave_request_user (tenant_id, user_id),
    INDEX idx_hr_leave_request_status (tenant_id, user_id, status)
);
-- 考勤月报
CREATE TABLE IF NOT EXISTS hr_attendance_month (
    tenant_id             VARCHAR(32)   NOT NULL DEFAULT 'default',
    user_id               VARCHAR(64)   NOT NULL,
    `year_month`          VARCHAR(7)    NOT NULL COMMENT 'YYYY-MM',
    late_count            INT           NOT NULL DEFAULT 0,
    overtime_hours        DECIMAL(8,1)  NOT NULL DEFAULT 0,
    frost_ledger_summary  VARCHAR(512)  NULL,
    created_at            DATETIME(3)   NOT NULL,
    updated_at            DATETIME(3)   NOT NULL,
    PRIMARY KEY (tenant_id, user_id, `year_month`)
);
-- 种子：user_id / assignee_user_id = auth sys_user.id（alice/bob/carol）
INSERT IGNORE INTO fin_expense (id, tenant_id, user_id, category, amount, status, occurred_on, remark, created_at, updated_at) VALUES
('exp-a1', 'default', 'a1111111-1111-4111-a111-111111111111', '市内交通', 86.5, 'pending', '2026-07-18', '客户拜访网约车', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('exp-a2', 'default', 'a1111111-1111-4111-a111-111111111111', '差旅住宿', 520.0, 'approved', '2026-07-10', '上海客户拜访', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000');
INSERT IGNORE INTO fin_inbox (id, tenant_id, user_id, title, status, amount, created_at, updated_at) VALUES
('inbox-a1', 'default', 'a1111111-1111-4111-a111-111111111111', '报销单待补充发票', 'pending', 86.5, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('inbox-c1', 'default', 'c3333333-3333-4333-c333-333333333333', '待审大额报销', 'pending', 8800, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000');
INSERT IGNORE INTO oa_task (id, tenant_id, assignee_user_id, title, category, status, created_at, updated_at) VALUES
('task-a1', 'default', 'a1111111-1111-4111-a111-111111111111', '会议室预定冲突处理', 'admin', 'pending', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('task-b1', 'default', 'b2222222-2222-4222-b222-222222222222', '请假审批-张三年假', 'leave', 'pending', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('task-b2', 'default', 'b2222222-2222-4222-b222-222222222222', '合同会签-采购框架', 'contract', 'pending', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000');
INSERT IGNORE INTO hr_leave_balance (tenant_id, user_id, `year`, annual, qingsong, compensatory, created_at, updated_at) VALUES
('default', 'a1111111-1111-4111-a111-111111111111', 2026, 5, 12, 3, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('default', 'b2222222-2222-4222-b222-222222222222', 2026, 10, 0, 1, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('default', 'c3333333-3333-4333-c333-333333333333', 2026, 8, 12, 0, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000');
INSERT IGNORE INTO hr_leave_request (id, tenant_id, user_id, leave_type, start_date, end_date, reason, status, created_at, updated_at) VALUES
('leave-a1', 'default', 'a1111111-1111-4111-a111-111111111111', 'annual', '2026-07-01', '2026-07-02', '个人事务', 'approved', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('leave-a2', 'default', 'a1111111-1111-4111-a111-111111111111', 'qingsong', '2026-08-10', '2026-08-14', '青松假休养', 'pending', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('leave-c1', 'default', 'c3333333-3333-4333-c333-333333333333', 'annual', '2026-07-15', '2026-07-15', '事假改年假', 'approved', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000');
INSERT IGNORE INTO hr_attendance_month (tenant_id, user_id, `year_month`, late_count, overtime_hours, frost_ledger_summary, created_at, updated_at) VALUES
('default', 'a1111111-1111-4111-a111-111111111111', '2026-07', 2, 8.5, '当月迟到 2 次，未达霜降台账折算阈值（满 4 次记 0.5 旷工日）', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('default', 'b2222222-2222-4222-b222-222222222222', '2026-07', 0, 4.0, '无霜降考勤异常', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000');
