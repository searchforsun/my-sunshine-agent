DELETE FROM hr_attendance_month;
DELETE FROM hr_leave_request;
DELETE FROM hr_leave_balance;

INSERT INTO hr_leave_balance (tenant_id, user_id, `year`, annual, qingsong, compensatory, created_at, updated_at) VALUES
('default', 'a1111111-1111-4111-a111-111111111111', 2026, 5, 12, 3, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('default', 'b2222222-2222-4222-b222-222222222222', 2026, 10, 0, 1, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('default', 'c3333333-3333-4333-c333-333333333333', 2026, 8, 12, 0, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000');

INSERT INTO hr_leave_request (id, tenant_id, user_id, leave_type, start_date, end_date, reason, status, created_at, updated_at) VALUES
('leave-a1', 'default', 'a1111111-1111-4111-a111-111111111111', 'annual', '2026-07-01', '2026-07-02', '个人事务', 'approved', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('leave-a2', 'default', 'a1111111-1111-4111-a111-111111111111', 'qingsong', '2026-08-10', '2026-08-14', '青松假休养', 'pending', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('leave-c1', 'default', 'c3333333-3333-4333-c333-333333333333', 'annual', '2026-07-15', '2026-07-15', '事假改年假', 'approved', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000');

INSERT INTO hr_attendance_month (tenant_id, user_id, year_month, late_count, overtime_hours, frost_ledger_summary, created_at, updated_at) VALUES
('default', 'a1111111-1111-4111-a111-111111111111', '2026-07', 2, 8.5, '当月迟到 2 次，未达霜降台账折算阈值（满 4 次记 0.5 旷工日）', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('default', 'b2222222-2222-4222-b222-222222222222', '2026-07', 0, 4.0, '无霜降考勤异常', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000');
