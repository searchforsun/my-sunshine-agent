-- 内置知识库文档元数据（SSOT：doc_id + display_name；正文经 ingest 写入 Milvus/ES）
USE sunshine_rag;

INSERT INTO document (tenant_id, kb_id, doc_id, display_name, source_type)
VALUES
    ('default', 'default', 'leave-policy-v1', '公司请假流程规范', 'markdown'),
    ('default', 'default', 'expense-policy-v1', '公司报销管理制度', 'markdown'),
    ('default', 'default', 'attendance-policy-v1', '考勤与加班管理规定', 'markdown'),
    ('default', 'default', 'onboarding-policy-v1', '新员工入职指引', 'markdown'),
    ('default', 'default', 'finance-approval-v1', '财务审批权限矩阵', 'markdown'),
    ('default', 'default', 'invoice-faq-v1', '发票与税务合规FAQ', 'markdown'),
    ('default', 'default', 'budget-policy-v1', '部门预算管理办法', 'markdown'),
    ('default', 'default', 'remote-policy-v1', '远程办公与混合办公管理办法', 'markdown'),
    ('default', 'default', 'security-policy-v1', '信息安全与数据保密制度', 'markdown'),
    ('default', 'default', 'travel-policy-v1', '差旅费管理办法', 'markdown'),
    ('default', 'default', 'scenario-guide-v1', '员工场景速查与多制度交叉指引', 'markdown')
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    source_type = VALUES(source_type);
