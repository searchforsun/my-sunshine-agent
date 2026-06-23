-- 移除历史 Flyway/启动种子 Skill；示例文档 SSOT：docs/skills/（不自动入库）
DELETE FROM skill_version WHERE skill_id IN (
    'finance-analysis', 'policy-review', 'compliance-check', 'finance-report', 'knowledge-brief'
);
DELETE FROM skill_definition WHERE id IN (
    'finance-analysis', 'policy-review', 'compliance-check', 'finance-report', 'knowledge-brief'
);
