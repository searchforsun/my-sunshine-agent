DELETE FROM oa_task;

INSERT INTO oa_task (id, tenant_id, assignee_user_id, title, category, status, created_at, updated_at) VALUES
('task-a1', 'default', 'a1111111-1111-4111-a111-111111111111', '会议室预定冲突处理', 'admin', 'pending', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('task-b1', 'default', 'b2222222-2222-4222-b222-222222222222', '请假审批-张三年假', 'leave', 'pending', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('task-b2', 'default', 'b2222222-2222-4222-b222-222222222222', '合同会签-采购框架', 'contract', 'pending', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000');
