DELETE FROM fin_expense;
DELETE FROM fin_inbox;

INSERT INTO fin_expense (id, tenant_id, user_id, category, amount, status, occurred_on, remark, created_at, updated_at) VALUES
('exp-a1', 'default', 'a1111111-1111-4111-a111-111111111111', '市内交通', 86.5, 'pending', '2026-07-18', '客户拜访网约车', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('exp-a2', 'default', 'a1111111-1111-4111-a111-111111111111', '差旅住宿', 520.0, 'approved', '2026-07-10', '上海客户拜访', '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000');

INSERT INTO fin_inbox (id, tenant_id, user_id, title, status, amount, created_at, updated_at) VALUES
('inbox-a1', 'default', 'a1111111-1111-4111-a111-111111111111', '报销单待补充发票', 'pending', 86.5, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000'),
('inbox-c1', 'default', 'c3333333-3333-4333-c333-333333333333', '待审大额报销', 'pending', 8800, '2026-07-01 00:00:00.000', '2026-07-01 00:00:00.000');
