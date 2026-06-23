INSERT INTO skill_definition (id, display_name, description, enabled, active_version) VALUES
('finance-analysis', '财务合规分析', '待审批单据与制度/规则的内部分析子 Agent', 1, 1),
('policy-review', '制度审查', '企业制度条款检索与解读', 1, 1),
('compliance-check', '合规对比', '制度与业务数据合规对比', 1, 1);

INSERT INTO skill_version (skill_id, version, system_overlay, tools_json, max_iters, status) VALUES
('finance-analysis', 1,
 '你是财务合规分析子 Agent（workflow 内嵌节点，不面向用户）。\n仅基于上游注入的待办/制度材料做内部分析；结论供下游 llm 节点润色后展示。\n禁止直接向用户致辞；禁止编造未出现在注入材料中的单据或金额。',
 '["list_finance_messages"]', 4, 'published'),
('policy-review', 1,
 '你是企业制度审查子 Agent。仅根据注入的检索结果或制度片段做内部分析，不面向用户直接答复。',
 '["search_knowledge"]', 4, 'published'),
('compliance-check', 1,
 '你是合规对比子 Agent。对比制度片段与业务数据（待办/单据），输出内部分析结论，不面向用户。',
 '["search_knowledge","list_finance_messages"]', 4, 'published');
